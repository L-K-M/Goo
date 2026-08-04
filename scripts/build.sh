#!/usr/bin/env bash
# Build the Goo APK from the command line and stage it into dist/.
#
# Release build by default; --debug builds the debug-variant APK instead.
# Both variants are signed with the checked-in debug keystore (deliberate,
# see docs/decisions/0002), so the staged APK is installable as-is. It is
# named after the committed versionName.
#
# The lkm-build engine (https://github.com/L-K-M/release-tool) has no Gradle
# kind, so this is a self-contained orchestrator in the family house style.
#
#   scripts/build.sh                  # incremental release build -> dist/
#   scripts/build.sh --debug          # debug build -> dist/
#   scripts/build.sh --clean          # wipe Gradle build output first
#   scripts/build.sh --check          # print resolved config; build nothing
#
# Usage: scripts/build.sh [--debug] [--clean] [--check]
# Requirements: JDK 17+; the Android SDK (local.properties or ANDROID_HOME).
set -euo pipefail

# Absolute self-path first: usage() re-opens the script, which a relative $0
# would no longer find after the cd below.
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELF")/.."

usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

VARIANT="release"
CLEAN=0
CHECK=0
for arg in "$@"; do
  case "$arg" in
    --debug) VARIANT="debug" ;;
    --clean) CLEAN=1 ;;
    --check) CHECK=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "!! unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*$/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$VERSION" ] || { echo "!! could not read versionName from app/build.gradle.kts" >&2; exit 1; }

if [ "$VARIANT" = "release" ]; then
  TASK="assembleRelease"
  APK="app/build/outputs/apk/release/app-release.apk"
  OUT="dist/goo-v${VERSION}-release.apk"
else
  TASK="assembleDebug"
  APK="app/build/outputs/apk/debug/app-debug.apk"
  OUT="dist/goo-v${VERSION}-debug.apk"
fi

if [ "$CHECK" -eq 1 ]; then
  echo "==> config"
  echo "-- variant:  $VARIANT"
  echo "-- version:  $VERSION"
  echo "-- task:     ./gradlew $TASK"
  echo "-- staged:   $OUT"
  exit 0
fi

if [ "$CLEAN" -eq 1 ]; then
  echo "==> ./gradlew clean"
  ./gradlew clean
fi

echo "==> ./gradlew $TASK"
./gradlew "$TASK"

[ -f "$APK" ] || { echo "!! expected APK not found: $APK" >&2; exit 1; }

mkdir -p dist
cp "$APK" "$OUT"
echo "==> staged $OUT"

# Reveal in Finder when running on a Mac desktop; harmless elsewhere.
command -v open >/dev/null 2>&1 && open -R "$OUT" 2>/dev/null || true
echo "==> Done."
