#!/usr/bin/env bash
#
# Create the release keystore used to sign every distributed APK.
#
# The SAME key must be used for every build, forever. If the key changes, a new APK can
# never be installed over an already published one, which breaks in-place updates.
#
# The keystore is NEVER committed. It is stored as the GitHub secret KEYSTORE_BASE64
# together with KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD.
#
# Usage:
#   ./scripts/setup-signing.sh                 # generate a new keystore
#   ./scripts/setup-signing.sh --out /tmp/x.jks
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_JKS="${REPO_ROOT}/release-keystore.jks"
ALIAS="${KEY_ALIAS:-cloud-monitor}"
STORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${KEY_PASSWORD:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT_JKS="$2"; shift 2 ;;
    --alias) ALIAS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -f "${OUT_JKS}" ]]; then
  echo "ERROR: ${OUT_JKS} already exists." >&2
  echo "Never overwrite the release key: an existing APK could no longer be updated." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool not found. Install a JDK (17 or newer) and make sure it is on PATH." >&2
  exit 1
fi

if [[ -z "${STORE_PASSWORD}" ]]; then
  # Generate a random password when the caller did not provide one.
  STORE_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=' | head -c 40)"
  KEY_PASSWORD="${STORE_PASSWORD}"
  GENERATED_PASSWORD=true
fi

echo "Creating release keystore ${OUT_JKS}"
keytool -genkeypair \
  -keystore "${OUT_JKS}" \
  -storetype JKS \
  -alias "${ALIAS}" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -storepass "${STORE_PASSWORD}" \
  -keypass "${KEY_PASSWORD}" \
  -dname "CN=Cloud Monitor System, OU=Release, O=cloud-monitor-system, L=, ST=, C=US"

echo
echo "Keystore created."
echo
echo "=== ADD THESE AS GITHUB SECRETS (never commit the keystore) ==="
echo
# Never print passwords/keys to stdout: doing so would leak them into CI logs, shell
# history and screen shares. Write them to a 0600 file outside version control instead.
base64 -w0 "${OUT_JKS}" > "${OUT_JKS}.base64"
SECRETS_FILE="${OUT_JKS}.secrets"
umask 077
{
  echo "KEYSTORE_PASSWORD=${STORE_PASSWORD}"
  echo "KEY_ALIAS=${ALIAS}"
  echo "KEY_PASSWORD=${KEY_PASSWORD}"
} > "${SECRETS_FILE}"
chmod 600 "${SECRETS_FILE}"
echo "  KEYSTORE_BASE64   -> full contents of ${OUT_JKS}.base64"
echo "  KEYSTORE_PASSWORD -> ${SECRETS_FILE}"
echo "  KEY_ALIAS         -> ${SECRETS_FILE}"
echo "  KEY_PASSWORD      -> ${SECRETS_FILE}"
echo
echo "Secrets were written to ${SECRETS_FILE} (mode 600). Do NOT paste them into logs,"
echo "issues, terminal history or source files. Back them up in a password manager"
echo "OUTSIDE the repository. If you lose them you can never publish an update for the"
echo "already installed apps."
echo
if command -v gh >/dev/null 2>&1; then
  echo "Upload them with gh (values are read from the protected files, never echoed):"
  echo "  gh secret set KEYSTORE_BASE64 < \"${OUT_JKS}.base64\""
  echo "  gh secret set KEYSTORE_PASSWORD < <(sed -n 's/^KEYSTORE_PASSWORD=//p' \"${SECRETS_FILE}\")"
  echo "  gh secret set KEY_ALIAS < <(sed -n 's/^KEY_ALIAS=//p' \"${SECRETS_FILE}\")"
  echo "  gh secret set KEY_PASSWORD < <(sed -n 's/^KEY_PASSWORD=//p' \"${SECRETS_FILE}\")"
  echo
fi

echo "SHA-256 of the signing certificate:"
keytool -list -v -keystore "${OUT_JKS}" -alias "${ALIAS}" -storepass "${STORE_PASSWORD}" \
  | grep -E "SHA256:" || true
