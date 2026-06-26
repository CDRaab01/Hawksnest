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
- **Phase 3** ✅ — biometric gate (unlock/disarm, `security/`), camera snapshots + live MJPEG
  (`ui/cameras/`), alarm PIN keypad (`ui/home/AlarmKeypad.kt`).
- **Phase 4** ⏳ — push notifications (custom FCM pipeline + owner-authored HA automation). Not yet
  built.

> Coverage today is strongest on the pure logic (`core/logic`, `core/ha`) and security gate, which
> are JVM-unit-tested. The Compose UI also runs through the **Sift design-slop audit** (below).

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
