#!/usr/bin/env bash
# Ensures an Android SDK is available so Claude Code web sessions can build,
# lint, and test this project. Idempotent and safe to re-run; installs only
# what's missing.
set -euo pipefail

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_sdkmanager() {
  if command -v sdkmanager >/dev/null 2>&1; then command -v sdkmanager; return 0; fi
  for c in "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
           "$SDK_DIR/cmdline-tools/bin/sdkmanager"; do
    [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

write_local_properties() {
  echo "sdk.dir=$SDK_DIR" > "$REPO_ROOT/local.properties"
}

if [ -d "$SDK_DIR/platforms/android-37.0" ] && find_sdkmanager >/dev/null 2>&1; then
  echo "Android SDK already present at $SDK_DIR"
  write_local_properties
  exit 0
fi

echo "Installing Android SDK into $SDK_DIR ..."
mkdir -p "$SDK_DIR/cmdline-tools"
if ! find_sdkmanager >/dev/null 2>&1; then
  TMP_ZIP="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
  unzip -q -o "$TMP_ZIP" -d "$SDK_DIR/cmdline-tools"
  rm -f "$TMP_ZIP"
  # The archive extracts to "cmdline-tools/"; normalize to ".../latest".
  if [ -d "$SDK_DIR/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  fi
fi

SDKMANAGER="$(find_sdkmanager)"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
  "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" >/dev/null

write_local_properties
echo "Android SDK ready at $SDK_DIR"
