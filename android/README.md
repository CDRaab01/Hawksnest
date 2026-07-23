# Hawksnest — Android app

A native **Kotlin + Jetpack Compose** client for Hawksnest, the custom Home Assistant front-end.
It lives in the same repo as the web interface and reuses the **PULSE** design system (lifted from
the sibling Spotter app). The web PWA and this native app coexist — web for desktop, native for
phone.

Home Assistant stays the backend/brain. The app talks to HA's WebSocket + REST APIs directly
(no Hawksnest server of its own), reaching HA through the existing Hawksnest reverse-proxy host
over Tailscale, authenticated with a HA **long-lived access token**.

## Status — Phases 0–4 code-complete (push on-device verify pending)

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
- **Devices v2** ✅ — the Devices tab redesigned as a single-column, three-tier rhythm per room
  (featured lock/climate/alarm cards; compact control rows with inline optimistic switches;
  read-only state rows), PULSE segment chips, room summaries, search, and long-press →
  rename/hide (persisted on-device, `DevicePrefsStore`). The Ring-vs-ring-mqtt double exposure
  is deduped centrally at the source layer (`core/logic/Dedupe.kt`) so every screen sees one
  entity per physical device.
- **Phase 4** ✅ (code) — push notifications via **self-hosted ntfy** (not FCM: no Google
  dependency, tailnet-only). `push/NtfyPushService` is a `specialUse` foreground service that holds
  one streaming connection to `<base>/<topic>/json` and raises per-kind notifications
  (`PushNotifier`); `NtfyMessage`/`PushRoute` (parse + doorbell→cameras / alarm→home routing) are
  pure and JVM-unit-tested. Off by default — opt in under **Settings → Notifications** (requests
  `POST_NOTIFICATIONS`); `BootReceiver` restarts it after a reboot if enabled. The ntfy server +
  the HA automations that publish doorbell/alarm events live in the **`hawksnest-automation`** repo
  (`docs/ntfy-push.md`). **On-device runtime** (delivery with the app closed, battery, reconnect,
  the notification tap) is the one seam unit tests can't cover — smoke-test it on the phone before
  relying on it (see the camera smoke checklist's "push fires" item).

- **Home-screen widgets** ✅ (code) — see below.

> Coverage today is strongest on the pure logic (`core/logic`, `core/ha`), which is JVM-unit-tested.
> The Compose UI also runs through the **Sift design-slop audit** (below).

## Home-screen widgets

Three widgets, added from the launcher's widget picker like any other. Each asks which device it
should control when you drop it, and each opens the app when you tap its name.

| Widget | What it does |
|---|---|
| **Hawksnest Light** | Tap to toggle. Dimmable lights also get −/+ buttons and a level bar. The steps are geared like a real dimmer — small near the bottom (1, 5, 10, 20…) where the eye notices, larger near the top (…65, 80, 100) where it doesn't. |
| **Hawksnest Lock** | One tap to lock. **Two taps to unlock** — the first arms "Tap again to unlock", which lapses after five seconds. |
| **Hawksnest Alarm** | Off / Home / Away. Arming is one tap; **disarming takes two**, the same way unlocking does. |

Things worth knowing before relying on them:

- **They only work on the tailnet**, like the rest of the app. Off it, a widget says "Can't reach
  Hawksnest — check Tailscale" and offers a retry; it does not show what the lock said last time.
- **Locks and the alarm never show a stale state.** A reading more than a minute old is dropped and
  the widget says "Checking…" while it refetches. This is deliberate and is the widget half of the
  no-stale-security-state invariant — "Locked" is the one word a widget must never guess. Lights
  are exempt: they keep their last reading and show its age once it is over fifteen minutes old.
- **The lock and alarm always show when they were read** — "Locked · 10:42". A widget's picture
  stays on the home screen until something redraws it, which may be a long time, so the picture
  has to date itself. If the time looks old, it is: tap it to refresh.
- **Nothing polls in the background.** A widget reads when it is drawn, after every action, and
  whenever you tap an error. While the app is open its widgets also follow the live socket, so
  opening Hawksnest makes the home screen snap current. That is the whole freshness story — the
  platform's own 30-minute update period is cosmetic.
- **Unlock and disarm are confirm-taps, not slides.** The in-app controls make you slide precisely
  so a pocket can't open the front door; widgets can't draw a slide, so a second tap stands in for
  the deliberate gesture.
- **The light picker lists lights only** — not `switch` entities, which on this house are mostly
  ring-mqtt camera plumbing (live streams, motion toggles, sirens) and drowned out the real lights.
  If a lamp you want is wired as a `switch` rather than a `light`, say so and it's a one-line change.
- **The picker hides what the Devices list hides.** It borrows the app's live connection to read
  HA's entity registry, so it drops diagnostic/config entities and collapses the duplicate a device
  gets from running both the Ring integration and ring-mqtt. Opened with no connection (off the
  tailnet), it still works from a plain REST read — the list is just longer.

On-device behaviour (placement, resize, and a tap landing while the app is dead) is the seam unit
tests can't reach — worth a smoke test after the first install that carries them.

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

Hawksnest reaches HA **only over HTTPS**. `res/xml/network_security_config.xml` sets
`cleartextTrafficPermitted="false"` — cleartext HTTP is disallowed in release builds.

The proxy is fronted by **Tailscale Serve** (TLS at `https://<host>.ts.net:8443`, a real
Let's Encrypt cert, tailnet-only; see `deploy/windows/hawksnest-serve.ps1`). Point **Settings →
HA URL** at that HTTPS address. (A `100.x` Tailscale IP won't work now — cleartext is off and the
cert is issued for the MagicDNS name — so always use the `*.ts.net:8443` hostname.)

The earlier deliberate `cleartext=true` existed because the HA host could be a bare CGNAT IP a
scoped `<domain-config>` can't match; TLS fronting the proxy removed that constraint. A
**debug-only** override (`src/debug/res/xml/network_security_config.xml`) still permits cleartext to
`10.0.2.2`/`localhost` for the instrumented mock-HA — it never ships in a release APK.
