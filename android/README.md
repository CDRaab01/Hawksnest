# Hawksnest — Android app

A native **Kotlin + Jetpack Compose** client for Hawksnest, the custom Home Assistant front-end.
It lives in the same repo as the web interface and reuses the **PULSE** design system (lifted from
the sibling Spotter app). The web PWA and this native app coexist — web for desktop, native for
phone.

Home Assistant stays the backend/brain. The app talks to HA's WebSocket + REST APIs directly
(no Hawksnest server of its own), reaching HA through the existing Hawksnest reverse-proxy host
over Tailscale, authenticated with a HA **long-lived access token**.

## Status — Phase 0 (scaffold)

What's here today: a themed app shell with bottom navigation (**Home · Cameras · History ·
Settings**) and placeholder screens. The full PULSE theme (`ui/theme/`, 9 files) and core
components (`ui/components/`) are in place. No HA connection yet.

Roadmap (see `/root/.claude/plans/…` / the approved plan):
- **Phase 1** — HA WebSocket client (`core/ha/`) + pure-logic ports (`core/logic/`: resolve, cards,
  density, areas, registry, alarm) + read-only Home/Area/Settings + a security "is the house
  secure?" hero. Demo/fixture mode with no token.
- **Phase 2** — control (`call_service`, non-optimistic) + remaining domain cards + entity detail/
  history + logbook timeline + customize.
- **Phase 3** — biometric gate (unlock/disarm), camera snapshots + live MJPEG, alarm PIN keypad.
- **Phase 4** — push notifications (custom FCM pipeline + owner-authored HA automation).

## Build

Requires the Android SDK (set `ANDROID_HOME`, or `sdk.dir` in `local.properties`). minSdk 26,
target/compile 35, Kotlin 2.0, Compose.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # CI does this; locally point at your SDK
./gradlew :app:testDebugUnitTest   # unit tests (no device/KVM)
./gradlew :app:assembleDebug       # debug APK → app/build/outputs/apk/debug/
```

Optionally prefill the HA base URL for debug builds: add `ha.url=http://<tailnet-host>:8080` to
`local.properties` (otherwise it's entered in Settings at runtime). CI runs both Gradle tasks
(`.github/workflows/android-ci.yml`).

## Networking note

Hawksnest reaches HA as cleartext HTTP to a private tailnet host, which Android blocks by default.
`res/xml/network_security_config.xml` permits cleartext; tighten it to your specific host (or front
the proxy with TLS) once your address is fixed — see the comments in that file.
