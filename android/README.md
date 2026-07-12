# Hawksnest — Android app

A native **Kotlin + Jetpack Compose** client for Hawksnest, the custom Home Assistant front-end.
It lives in the same repo as the web interface and reuses the **PULSE** design system (lifted from
the sibling Spotter app). The web PWA and this native app coexist — web for desktop, native for
phone.

Home Assistant stays the backend/brain. The app talks to HA's WebSocket + REST APIs directly
(no Hawksnest server of its own), reaching HA through the existing Hawksnest reverse-proxy host
over Tailscale, authenticated with a HA **long-lived access token**.

## Status — Phases 0–3 done; Phase 4 (push) pending

What's here today: a live HA client, not a scaffold. The full PULSE theme (`ui/theme/`) and core
components (`ui/components/`) are in place, plus:
- **Phase 1** ✅ — HA WebSocket client (`core/ha/`) + pure-logic ports (`core/logic/`: resolve,
  cards, density, areas, registry, alarm) + Home/Area/Settings + the security "is the house secure?"
  hero. Demo/fixture mode (`FixtureSource`) runs with no token.
- **Phase 2** ✅ — control (`call_service`, non-optimistic) + domain cards + entity detail/history +
  logbook timeline + customize.
- **Phase 3** ✅ — camera snapshots + live MJPEG (`ui/cameras/`). (An earlier biometric gate + alarm
  PIN keypad on unlock/disarm were removed — the home owner found the friction unwanted; disarm/unlock
  now fire directly. A panel that enforces HA `code_format` must allow codeless disarm from the app.)
- **Control feel** ✅ — every user-facing control call goes through `core/ha/ControlGate`
  (crash-safe: failures land on one app-level snackbar with a reject buzz; honest pending via
  `pendingControls`). Locks are a **slide-to-act** track (`ui/components/SlideToAct.kt` — the drag
  is the confirmation; the thumb holds a spinner until HA's echo; no accidental unlocks).
  Lights/switches/fans are **optimistic** (the thumb follows the finger; the echo reconciles;
  failures snap back). Semantic haptics throughout (`ui/components/Haptics.kt`).
- **Phase 4** ⏳ — push notifications (custom FCM pipeline + owner-authored HA automation). Not yet
  built.

> Coverage today is strongest on the pure logic (`core/logic`, `core/ha`), which is JVM-unit-tested.
> The Compose UI also runs through the **Sift design-slop audit** (below).

## Build

Requires the Android SDK (set `ANDROID_HOME`, or `sdk.dir` in `local.properties`). minSdk 26,
target/compile 35, Kotlin 2.0, Compose. No SDK handy? Run `bash scripts/setup-android-sdk.sh` from
the repo root — it installs a minimal SDK (platform-35 + build-tools 35) and writes
`android/local.properties`. (Run with `ANDROID_HOME` exported when using the Sift composite build,
so the included Sift build finds the SDK too.)

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # CI does this; locally point at your SDK
./gradlew :app:testDebugUnitTest   # unit tests (no device/KVM)
./gradlew :app:assembleDebug       # debug APK → app/build/outputs/apk/debug/
```

Optionally prefill the HA base URL for debug builds: add `ha.url=http://<tailnet-host>:8080` to
`local.properties` (otherwise it's entered in Settings at runtime). CI runs both Gradle tasks
(`.github/workflows/android-ci.yml`).

## Testing against the mock HA server

The app talks to HA the same way the web client does, so it can run against the repo's scriptable
fake Home Assistant — [`mock-ha/`](../mock-ha) — instead of a real instance. The HA base URL is
fully injectable (`util/CredentialStore` → `ConnectionManager`), and cleartext to private hosts is
already allowed (`res/xml/network_security_config.xml`), so no app changes are needed.

- **JVM unit tests (here, no device):** drive `core/ha/HaConnection` with a faked WebSocket and the
  mock's frame shapes (see `HaConnectionTest` for the pattern). The pure lock/label logic
  (`core/logic/LockState.kt`, `Security.kt`) is unit-tested directly.
- **Instrumented tests (need a KVM-accelerated emulator):** the committed suite is
  `app/src/androidTest/java/com/hawksnest/MockHaInstrumentedTest.kt` (launch, connect, arm-away
  outbound `call_service`, inbound state push), driving the real app through a small `/__scenario`
  client (`MockControl.kt`). One-shot runner — boots a headless emulator, builds + installs, runs the
  suite, tears down:

  ```bash
  bash scripts/android-emulator-test.sh            # instrumented suite
  bash scripts/android-emulator-test.sh --monkey   # + Monkey crash-fuzz (demo mode)
  ```

  It needs a one-time emulator image + AVD (commands in the script header) and Node for the mock.
  What it does, and why:
  1. `./gradlew --stop`, then builds the app + androidTest APKs **with the emulator off** — a Gradle
     daemon plus the emulator starves a low-RAM host, and the software-GL emulator SIGSEGVs under
     memory pressure when the app renders.
  2. Boots the AVD headless (`-gpu swiftshader_indirect -no-window -memory 2048`), starts the mock on
     `PORT=8799` (tests reach it via the loopback alias `http://10.0.2.2:8799`, entered through the
     Settings UI), installs both APKs, and runs the suite with `adb shell am instrument` — lighter and
     more reliable on a fragile headless emulator than `:app:connectedDebugAndroidTest` (the simpler
     one-liner if your emulator is robust).
  3. `--monkey` adds a demo-mode Monkey crash-fuzz (1200 events) and prints the `adb logcat -b crash`
     buffer.

  Scenarios are driven over the **same `/__scenario` control API** the web E2E uses (`reset`, `state`,
  `service-outcome`, `disconnect`, `calls`) — see [`mock-ha/README.md`](../mock-ha/README.md). This is
  the one fake backend both clients share.

  > Caveats: `/dev/kvm` must be usable. On a minimal/headless box the emulator's qemu may be missing
  > system libs (`libpulse0`, `libnss3`, `libnspr4`, `libxkbfile1`, …) and Java/Node may be off PATH —
  > put those fixes in `scripts/android-local-env.sh` (git-ignored; the runner sources it if present).
  > The WebRTC live-camera path isn't exercised against the mock (it serves no `web_rtc` camera), so
  > the camera timeline scrubber is only reachable with a real ring/go2rtc camera or device.

## Design audit (Sift)

The Compose UI is audited by **[Sift](../../Sift)**, the sibling design-slop auditor — it renders
representative screens on Robolectric and flags low contrast, tiny touch targets, overused fonts,
AI-tell palettes, and sloppy copy.

Wiring is a *test-only* composite build, gated so a standalone checkout (without Sift alongside)
still builds and unit-tests normally:
- `settings.gradle.kts` `includeBuild`s Sift from `siftDir` (default sibling `../../Sift`; CI passes
  `-PsiftDir=sift-src`).
- `app/build.gradle.kts` adds `style.sift:sift-compose` + the audit source set
  (`app/src/siftAudit/`) only when Sift is present (`siftAvailable`).
- The suite is `app/src/siftAudit/kotlin/com/hawksnest/sift/HawksnestDesignSlopTest.kt`; config +
  baseline live in `app/.sift/`.

Run it locally (with the Sift repo checked out next to Hawksnest):

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "*HawksnestDesignSlopTest"   # report → app/build/sift/report.json
```

In CI it runs as the **advisory, non-gating** `sift-audit` job in `android-ci.yml`, gated by the
committed baseline (`app/.sift/baseline.json`) so it only flags *new* slop. Both repos are public,
so the job checks Sift out with the default token; the optional `SIFT_REPO_TOKEN` secret is only
needed if the Sift repo is ever made private.

## Networking note

Hawksnest reaches HA as cleartext HTTP to a private tailnet host, which Android blocks by default.
`res/xml/network_security_config.xml` permits cleartext via a broad `base-config`.

This is a **deliberate Phase-0 choice, not an oversight**: the HA host is user-entered and may be a
MagicDNS name (`*.ts.net`) *or* a raw CGNAT IP (`100.x.y.z`). A scoped `<domain-config>` can match
the hostname form but **not** a bare IP, so tightening it risks silently breaking lock/disarm
connectivity for IP-based setups — unacceptable for a security app. Recommended hardening, in order:
1. Front the proxy with TLS (`https`/`wss`) and set `cleartextTrafficPermitted="false"` — best.
2. If staying cleartext, switch to the scoped `<domain-config>` example in the file **and** always
   use the MagicDNS hostname (never the bare IP).

See the comments in `res/xml/network_security_config.xml`.
