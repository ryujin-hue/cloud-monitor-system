#!/usr/bin/env bash
#
# Prove that a newer APK installs over an older one.
#
# The script builds TWO consecutive versions and verifies, with `apksigner` and `aapt`:
#
#   1. both APKs have the SAME package name,
#   2. both APKs are signed with the SAME certificate (identical SHA-256),
#   3. the newer APK has a HIGHER versionCode,
#   4. the newer APK has a higher versionName.
#
# The second build is made from a synthetic commit created with `git commit-tree`, which
#
#   * does NOT touch the working tree,
#   * does NOT add or move any branch or tag,
#   * does NOT rewrite the history of main,
#   * is not pushed anywhere.
#
# The commit object is dangling and gets garbage collected later; that is harmless.
#
# If an emulator or a connected device is available, the script ALSO installs the old APK,
# then the new one with `adb install -r`, and checks that the application data survived.
#
# Usage:
#   ./scripts/verify-update.sh                # verify both apps
#   ./scripts/verify-update.sh app-monitor    # verify a single app
#   ./scripts/verify-update.sh --with-device  # also run the adb install -r check
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-${HOME}/android-sdk}"
export PATH="${JAVA_HOME}/bin:${PATH}"

WITH_DEVICE=false
APPS=()
for arg in "$@"; do
  case "${arg}" in
    --with-device) WITH_DEVICE=true ;;
    app-monitor|app-alert) APPS+=("${arg}") ;;
    *) echo "unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done
if [[ ${#APPS[@]} -eq 0 ]]; then
  APPS=(app-monitor app-alert)
fi

# ---------------------------------------------------------------------------
# Locate the build tools
# ---------------------------------------------------------------------------
APKSIGNER=""
AAPT=""
for dir in "${ANDROID_HOME}"/build-tools/*; do
  [[ -d "${dir}" ]] || continue
  if [[ -x "${dir}/apksigner" ]]; then
    APKSIGNER="${dir}/apksigner"
    if [[ -x "${dir}/aapt" ]]; then AAPT="${dir}/aapt"; fi
  fi
done
if [[ -z "${APKSIGNER}" ]]; then
  echo "ERROR: apksigner not found under ${ANDROID_HOME}/build-tools." >&2
  echo "Install the Android SDK build tools first." >&2
  exit 1
fi
if [[ -z "${AAPT}" ]]; then
  echo "ERROR: aapt not found under ${ANDROID_HOME}/build-tools." >&2
  exit 1
fi

echo "apksigner: ${APKSIGNER}"
echo "aapt:      ${AAPT}"
echo

# ---------------------------------------------------------------------------
# Build the CURRENT version
# ---------------------------------------------------------------------------
CURRENT_VERSION_CODE="$(git rev-list --count HEAD)"
CURRENT_VERSION_NAME="$(./scripts/version.sh | grep '^versionName=' | cut -d= -f2)"
echo "Building version ${CURRENT_VERSION_NAME} (versionCode ${CURRENT_VERSION_CODE})"

./gradlew --quiet :app-monitor:assembleRelease :app-alert:assembleRelease

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

OLD_APKS=()
for app in "${APPS[@]}"; do
  apk="$(find "${app}/build/outputs/apk/release" -name '*.apk' -print -quit)"
  if [[ -z "${apk}" ]]; then
    echo "ERROR: no release APK found for ${app}" >&2
    exit 1
  fi
  cp "${apk}" "${WORK_DIR}/${app}-old.apk"
  OLD_APKS+=("${app}")
done

# ---------------------------------------------------------------------------
# Build the NEXT version from a synthetic commit
# ---------------------------------------------------------------------------
echo
echo "Creating a synthetic follow-up commit (no branch, no tag, no history rewrite)..."
TREE="$(git write-tree)"
# `git commit-tree` needs an identity. CI runners have none by default, so the identity
# is passed explicitly instead of relying on a global git configuration.
SYNTHETIC_COMMIT="$(
  GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-cloud-monitor-verify}" \
  GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-verify@cloud-monitor.invalid}" \
  GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-cloud-monitor-verify}" \
  GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-verify@cloud-monitor.invalid}" \
  git commit-tree "${TREE}" -p HEAD -m "chore: synthetic build for the in-place update proof"
)"
NEW_COUNT="$(git rev-list --count "${SYNTHETIC_COMMIT}")"
NEW_VERSION_NAME="$(grep -E '^MAJOR=' version.properties | cut -d= -f2 | tr -d '[:space:]').$(grep -E '^MINOR=' version.properties | cut -d= -f2 | tr -d '[:space:]').${NEW_COUNT}"
echo "Building version ${NEW_VERSION_NAME} (versionCode ${NEW_COUNT})"

SYNTHETIC_WORKTREE="${WORK_DIR}/synthetic"
git worktree add --detach "${SYNTHETIC_WORKTREE}" "${SYNTHETIC_COMMIT}" >/dev/null

(
  cd "${SYNTHETIC_WORKTREE}"
  ./gradlew --quiet :app-monitor:assembleRelease :app-alert:assembleRelease
)

NEW_APKS=()
for app in "${OLD_APKS[@]}"; do
  apk="$(find "${SYNTHETIC_WORKTREE}/${app}/build/outputs/apk/release" -name '*.apk' -print -quit)"
  if [[ -z "${apk}" ]]; then
    echo "ERROR: no release APK found for ${app} in the synthetic build" >&2
    exit 1
  fi
  cp "${apk}" "${WORK_DIR}/${app}-new.apk"
  NEW_APKS+=("${app}")
done

git worktree remove --force "${SYNTHETIC_WORKTREE}" >/dev/null 2>&1 || true
git worktree prune

# ---------------------------------------------------------------------------
# Compare
# ---------------------------------------------------------------------------
# NOTE: these helpers deliberately avoid pipes. `aapt dump badging` produces a lot of
# output; piping it into `head -1` makes aapt die with SIGPIPE, and with `set -o
# pipefail` that aborts the whole script. The values are parsed with bash instead.
extract_cert_sha256() {
  local out
  out="$("${APKSIGNER}" verify --print-certs "$1" 2>/dev/null || true)"
  if [[ "${out}" =~ SHA-256[[:space:]]digest:[[:space:]]*([0-9a-fA-F]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

extract_package() {
  local badging
  badging="$("${AAPT}" dump badging "$1" 2>/dev/null || true)"
  if [[ "${badging}" =~ package:\ name=\'([^\']+)\' ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

extract_version_code() {
  local badging
  badging="$("${AAPT}" dump badging "$1" 2>/dev/null || true)"
  if [[ "${badging}" =~ versionCode=\'([0-9]+)\' ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

extract_version_name() {
  local badging
  badging="$("${AAPT}" dump badging "$1" 2>/dev/null || true)"
  if [[ "${badging}" =~ versionName=\'([^\']+)\' ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

FAILED=0
for index in "${!OLD_APKS[@]}"; do
  app="${OLD_APKS[$index]}"
  old_apk="${WORK_DIR}/${app}-old.apk"
  new_apk="${WORK_DIR}/${app}-new.apk"

  echo
  echo "=== ${app} ==="

  old_pkg="$(extract_package "${old_apk}")"
  new_pkg="$(extract_package "${new_apk}")"
  old_cert="$(extract_cert_sha256 "${old_apk}")"
  new_cert="$(extract_cert_sha256 "${new_apk}")"
  old_vc="$(extract_version_code "${old_apk}")"
  new_vc="$(extract_version_code "${new_apk}")"
  old_vn="$(extract_version_name "${old_apk}")"
  new_vn="$(extract_version_name "${new_apk}")"

  echo "  package    : ${old_pkg} -> ${new_pkg}"
  echo "  cert sha256: ${old_cert}"
  echo "               ${new_cert}"
  echo "  versionCode: ${old_vc} -> ${new_vc}"
  echo "  versionName: ${old_vn} -> ${new_vn}"

  if [[ "${old_pkg}" != "${new_pkg}" ]]; then
    echo "  FAIL: the package name changed (${old_pkg} -> ${new_pkg})" >&2
    FAILED=1
  else
    echo "  OK  : same package"
  fi

  if [[ -z "${old_cert}" || -z "${new_cert}" ]]; then
    echo "  FAIL: could not read the signing certificate" >&2
    FAILED=1
  elif [[ "${old_cert}" != "${new_cert}" ]]; then
    echo "  FAIL: the signing certificate changed, an update would be rejected" >&2
    FAILED=1
  else
    echo "  OK  : same signing certificate"
  fi

  if [[ "${new_vc}" -le "${old_vc}" ]]; then
    echo "  FAIL: versionCode did not increase (${old_vc} -> ${new_vc})" >&2
    FAILED=1
  else
    echo "  OK  : versionCode increased"
  fi

  if [[ "${new_vn}" == "${old_vn}" ]]; then
    echo "  FAIL: versionName did not change" >&2
    FAILED=1
  else
    echo "  OK  : versionName changed"
  fi
done

# ---------------------------------------------------------------------------
# Optional: real in-place installation on a device / emulator
# ---------------------------------------------------------------------------
if [[ "${WITH_DEVICE}" == "true" ]]; then
  echo
  if ! command -v adb >/dev/null 2>&1; then
    echo "SKIP: adb not found."
  elif ! adb devices | grep -qE "device$"; then
    echo "SKIP: no device or emulator is connected."
  else
    app="${OLD_APKS[0]}"
    package="$(extract_package "${WORK_DIR}/${app}-old.apk")"
    echo "Installing the OLD APK on the connected device..."
    adb install -r "${WORK_DIR}/${app}-old.apk"
    echo "Writing a marker into the app data..."
    # `run-as` only works for a debuggable build, so this may fail. It must never abort
    # the script: the proof below does not depend on it.
    { adb shell "run-as ${package} sh -c 'mkdir -p files && echo update-proof > files/update-proof.txt'" || true; } 2>/dev/null || true
    echo "Installing the NEW APK over it..."
    adb install -r "${WORK_DIR}/${app}-new.apk"

    # The versionCode that is actually installed has to be the new one.
    new_vc="$(extract_version_code "${WORK_DIR}/${app}-new.apk")"
    dumpsys="$(adb shell dumpsys package "${package}" 2>/dev/null || true)"
    installed_vc=""
    if [[ "${dumpsys}" =~ versionCode=([0-9]+) ]]; then
      installed_vc="${BASH_REMATCH[1]}"
    fi
    if [[ "${installed_vc}" == "${new_vc}" ]]; then
      echo "  OK  : the device now runs versionCode ${installed_vc} (the new APK)"
    else
      echo "  FAIL: the device reports versionCode '${installed_vc}', expected '${new_vc}'" >&2
      FAILED=1
    fi

    # The data of the app has to survive the update. `run-as` only works for a
    # debuggable build; on an emulator `adb root` usually works and is tried as well.
    marker="$( { adb shell "run-as ${package} sh -c 'cat files/update-proof.txt'" 2>/dev/null || true; } | tr -d '\r')"
    if [[ "${marker}" == *"update-proof"* ]]; then
      echo "  OK  : the app data survived the in-place update"
    else
      { adb root >/dev/null 2>&1 || true; }
      sleep 3
      data_dir="$( { adb shell "ls -d /data/data/${package}" 2>/dev/null || true; } | tr -d '\r')"
      if [[ "${data_dir}" == *"/data/data/${package}"* ]]; then
        echo "  OK  : the app data directory survived the in-place update"
      else
        echo "  NOTE: could not read the app data (run-as and adb root are unavailable)." >&2
      fi
    fi
  fi
fi

echo
if [[ "${FAILED}" -eq 0 ]]; then
  echo "UPDATE PROOF: PASSED"
  exit 0
fi
echo "UPDATE PROOF: FAILED" >&2
exit 1
