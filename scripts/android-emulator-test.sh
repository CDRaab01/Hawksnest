#!/usr/bin/env bash
# Run the Android instrumented E2E suite (MockHaInstrumentedTest) — and, optionally, a Monkey
# crash-fuzz — against the bundled mock Home Assistant on a headless emulator. Derived from a
# verified run; safe to re-run.
#
#   bash scripts/android-emulator-test.sh             # instrumented suite only
#   bash scripts/android-emulator-test.sh --monkey    # + Monkey crash-fuzz (1200 events, demo mode)
#
# Prereqs:
#   - JDK 17 + Android SDK (run scripts/setup-android-sdk.sh once).
#   - The emulator package, a system image, and an AVD. One-time:
#       sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"
#       echo no | avdmanager create avd -n hawksnest_test \
#         -k "system-images;android-35;google_apis;x86_64" -d pixel_6
#   - Node (for the mock) and, ideally, KVM (/dev/kvm) for a usable emulator.
#
# Machine-specific env (e.g. a JAVA_HOME or LD_LIBRARY_PATH workaround for a minimal/headless box
# that's missing the emulator's system libs — libpulse0, libnss3, libnspr4, libxkbfile1, …) belongs
# in scripts/android-local-env.sh, which is git-ignored and sourced below if present.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$repo_root/scripts/android-local-env.sh" ] && . "$repo_root/scripts/android-local-env.sh"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
AVD="${AVD:-hawksnest_test}"
MOCK_PORT="${MOCK_PORT:-8799}"
EMU="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
# Debug builds carry the `.debug` applicationIdSuffix (see android/app/build.gradle.kts) so they
# coexist with a Play-installed release; the instrumented test + Monkey target that package.
PKG=com.hawksnest.debug
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

[ -x "$EMU" ] || { echo "No emulator at $EMU — install it: sdkmanager 'emulator' (see header)"; exit 1; }
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD" \
  || { echo "AVD '$AVD' not found — create it (see header)."; exit 1; }

cleanup() {
  "$ADB" emu kill >/dev/null 2>&1 || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$repo_root/android"

# 1) Build APKs with the emulator OFF. A Gradle daemon (~1.5G) + the emulator (~2-3G) starves a
#    low-RAM host, and the software-GL emulator SIGSEGVs under memory pressure when the app renders.
echo "==> building app + androidTest APKs (emulator off)"
./gradlew --stop || true
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PsiftDir=/nonexistent || exit 1

# 2) Boot the emulator headless (software GL; Vulkan-on-swiftshader is the usual headless crash).
echo "==> booting emulator @$AVD (headless)"
rm -f "$HOME/.android/avd/$AVD.avd/"*.lock 2>/dev/null || true
"$EMU" "@$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 2048 >/tmp/hawksnest-emulator.log 2>&1 &
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "    booted."

# 3) Start the mock HA (tests reach it via the loopback alias 10.0.2.2:$MOCK_PORT).
echo "==> starting mock-ha on :$MOCK_PORT"
( cd "$repo_root" && PORT="$MOCK_PORT" npm run mock-ha >/tmp/hawksnest-mock.log 2>&1 ) &
MOCK_PID=$!
until curl -fsS "http://localhost:$MOCK_PORT/__scenario/health" >/dev/null 2>&1; do sleep 1; done

# 4) Install both APKs and run the suite via `am instrument` (lighter + more reliable on a fragile
#    headless emulator than gradle :app:connectedDebugAndroidTest).
echo "==> installing APKs + running instrumented suite"
"$ADB" install -r -g "$APP"
"$ADB" install -r -g "$TEST"
curl -fsS -XPOST "http://localhost:$MOCK_PORT/__scenario/reset" \
  -H 'content-type: application/json' -d '{"scenario":"default"}' >/dev/null
"$ADB" shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner"

# 5) Optional Monkey crash-fuzz in demo mode (no creds → bundled fixtures, working cameras).
if [ "${1:-}" = "--monkey" ]; then
  echo "==> Monkey crash-fuzz (demo mode, 1200 events)"
  "$ADB" shell pm clear "$PKG" >/dev/null
  "$ADB" logcat -b crash -c || true
  "$ADB" shell monkey -p "$PKG" --pct-syskeys 0 --throttle 200 --kill-process-after-error -s 9001 -v 1200
  echo "--- crash buffer ---"
  "$ADB" logcat -b crash -d 2>/dev/null | grep -iE "FATAL|$PKG" || echo "(no app crashes)"
fi

echo "==> done."
