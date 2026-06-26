#!/usr/bin/env bash
# Idempotently install a minimal Android SDK and point Gradle at it, so Claude Code on the web
# (and any fresh checkout) can run the Android unit tests + the Sift design audit locally without a
# device. Safe to re-run: it skips work that's already done.
#
# Wired as a SessionStart hook in .claude/settings.json. Also runnable by hand:
#   bash scripts/setup-android-sdk.sh
set -euo pipefail

# Pinned to match android/app/build.gradle.kts (compileSdk 35) + libs.versions.toml (AGP 8.5.0).
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # cmdline-tools 12.0
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

install_cmdline_tools() {
  [ -x "$SDKMANAGER" ] && return 0
  echo "[setup-android-sdk] installing command-line tools → $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  local tmp; tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
}

install_packages() {
  # Skip if the target platform is already present.
  if [ -d "$ANDROID_HOME/platforms/android-35" ] && [ -d "$ANDROID_HOME/build-tools/35.0.0" ]; then
    return 0
  fi
  echo "[setup-android-sdk] accepting licenses + installing $PLATFORM $BUILD_TOOLS"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null
}

write_local_properties() {
  # Gradle reads sdk.dir from local.properties (no global env needed). Write it for the Android build.
  local lp="$repo_root/android/local.properties"
  if [ ! -f "$lp" ] || ! grep -q "^sdk.dir=" "$lp" 2>/dev/null; then
    echo "sdk.dir=$ANDROID_HOME" > "$lp"
    echo "[setup-android-sdk] wrote $lp"
  fi
}

install_cmdline_tools
install_packages
write_local_properties
echo "[setup-android-sdk] ready — ANDROID_HOME=$ANDROID_HOME"
