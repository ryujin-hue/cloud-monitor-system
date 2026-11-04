#!/usr/bin/env bash
#
# Print the version of the current build.
#
#   versionCode = git rev-list --count HEAD   (monotonic; requires fetch-depth: 0 in CI)
#   versionName = MAJOR.MINOR.<commit count>
#
# This mirrors the computation in settings.gradle.kts exactly.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

MAJOR="$(grep -E '^MAJOR=' version.properties | cut -d= -f2 | tr -d '[:space:]')"
MINOR="$(grep -E '^MINOR=' version.properties | cut -d= -f2 | tr -d '[:space:]')"
FALLBACK="$(grep -E '^FALLBACK_VERSION_CODE=' version.properties | cut -d= -f2 | tr -d '[:space:]')"

if COUNT="$(git rev-list --count HEAD 2>/dev/null)"; then
  VERSION_CODE="${COUNT}"
else
  if [[ "${CI:-}" == "true" ]]; then
    echo "ERROR: cannot compute versionCode from git history in CI." >&2
    echo "Check out the repository with 'fetch-depth: 0'." >&2
    exit 1
  fi
  VERSION_CODE="${FALLBACK}"
fi

VERSION_NAME="${MAJOR}.${MINOR}.${VERSION_CODE}"

echo "versionCode=${VERSION_CODE}"
echo "versionName=${VERSION_NAME}"
