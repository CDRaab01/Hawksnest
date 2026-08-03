# Audit follow-up — execution brief

Companion to [`AUDIT-2026-08.md`](../AUDIT-2026-08.md). This file is **paste-ready**: every item
inlines the facts it needs, so a fresh session can act on any single entry without reading the
audit or re-deriving anything.

**How to use it.** Paste one section (or one numbered item) into a new session as the prompt.
Items marked 🧑 **need the operator** — a phone in hand, or an edit to the live HA instance — and
cannot be done cold. Everything else is doable from the repo.

**Standing context for any item below:**
- Repo `C:\Code\Hawksnest`, branch from `origin/main`. Web: React/TS/Vite in `src/`. Android:
  Kotlin/Compose in `android/`. No Hawksnest backend — the app talks to Home Assistant directly.
- Live HA runs in k3s inside WSL. Read-only inspection:
  `wsl -d Dragonfly -u root -- kubectl … -n home-automation`. Under Git Bash, prefix
  `MSYS_NO_PATHCONV=1` when passing container paths, or the `/config/...` path gets mangled to a
  Windows path.
- **Update `ARCHITECTURE.md` in the same PR** as any architectural change — it is a repo convention.
- Tests: `npm run test` (vitest, 502 cases across 66 files, green as of 2026-08-02),
  `npm run test:e2e` (Playwright vs `mock-ha/`),
  `cd android && ./gradlew :app:testDebugUnitTest`.

---

## P0 — correctness and safety

### 1. 🧑 Frigate GenAI is failing on every event

Frigate logs `OpenAI returned an error: Error code: 400 - {'error': 'Model does not support
images.'}` every 20–40 seconds, continuously. The model behind `OPENAI_BASE_URL` (LM Studio on the
Windows host) has no vision capability, so **every AI event description and semantic-search
enrichment silently fails** — this is what feeds `EventDescription` in both clients and the
camera-object push text.

Do: load a vision-capable model in LM Studio (Qwen2.5-VL or similar) **or** set
`genai.enabled: false` in `hawksnest-automation/kustomize/base/frigate/configmap.yaml` until one is
loaded. Then extend the existing `hawksnest_frigate_detection_watchdog` automation — which today
checks only `switch.*_detect` and `sensor.*_camera_fps` — to alert on GenAI failure.

Verify: `wsl -d Dragonfly -u root -- kubectl logs -n home-automation deploy/frigate --since=10m | grep -i genai`
returns nothing.

### 2. 🧑 Duplicate automation ID kills a doorbell automation

Live `/config/automations.yaml` declares `- id: hawksnest_push_doorbell` at **line 29** (alias
`Hawksnest push: doorbell ding`, seeded, legacy `trigger:` key) and again at **line 57** (alias
`doorbell`, UI-created, modern `triggers:` key). HA logs:

```
Platform automation does not generate unique IDs. ID hawksnest_push_doorbell already exists
  - ignoring automation.doorbell
```

The UI-created one has never run. Do: decide which behaviour is wanted, delete the other, then fold
the survivor into the seed ConfigMap (item 3). Verify with the `grep -n "^- id:"` command in item 3.

### 3. Live HA config has drifted; git is no longer the source of truth

Live has **21 automations / 880 lines**; the seed ConfigMap
(`hawksnest-automation/kustomize/base/home-assistant/configmap.yaml`) has **6 / ~410**. The extra 15
— Cooper's and Master Bedroom ZEN32 scene control, WLED nursery and bedroom LED-bar stacks, All
Lock, Garage Door Unarm — exist only on the NFS PVC and never pass CI's `ha-config-check` job.
`scripts.yaml` live has `master_bedroom_strip_power_up`; the seed has `{}`. Live
`configuration.yaml` omits the pod CIDR `10.42.0.0/16` from `trusted_proxies` that the seed
includes.

Note the seed is a **first-boot-only** copy (an initContainer writes it only if the file is absent),
so reconciling git does not overwrite live — it is purely a durability fix.

Do, in `hawksnest-automation`:
1. Pull live `automations.yaml` / `scripts.yaml` / `configuration.yaml` into the seed ConfigMap.
   Read them out with
   `MSYS_NO_PATHCONV=1 wsl -d Dragonfly -u root -- kubectl exec -n home-automation deploy/home-assistant -c home-assistant -- sh -c 'cat /config/automations.yaml'`.
2. Add `.github/workflows/ha-config-drift.yml`, modelled on the existing `frigate-drift.yml`, diffing
   live vs seed on a schedule for those files. Same self-hosted runner labels
   (`[self-hosted, linux, dragonfly]`).
3. While in there: the `nursery_led_effect_alarm_too_hot` automation targets
   `device_id: 83fde5fed357870364f7dc7b9c127ac5` directly. Device IDs are registry-local and change
   on re-pair — this exact reference produced 1,686 errors before being hand-fixed on 2026-08-01.
   Prefer an `entity_id` target.

Verify: `MSYS_NO_PATHCONV=1 wsl -d Dragonfly -u root -- kubectl exec -n home-automation deploy/home-assistant -c home-assistant -- sh -c 'grep -c "^- id:" /config/automations.yaml'`
matches the seed's count, and the new workflow reports no drift.

### 4. Android suggests a URL the release build refuses

`android/app/src/main/java/com/hawksnest/ui/settings/SettingsScreen.kt:115`:

```kotlin
placeholder = { Text("http://hawksnest.<tailnet>.ts.net:8080") },
```

`res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"`, so a release build
cannot connect to that URL and the failure presents as a generic connection error.
`android/README.md` correctly documents `https://<host>.ts.net:8443`.

Compounding: `core/ha/HaSource.kt:524` maps a schemeless host to cleartext —
`else -> "ws://$trimmed"`.

Do: change the placeholder to `https://hawksnest.<tailnet>.ts.net:8443`, and change the `else` branch
to `wss://`. Add a `wsUrl` unit test case for the schemeless input. If plain `ws://` is ever wanted,
the user can type it explicitly.

Verify: `cd android && ./gradlew :app:testDebugUnitTest`.

### 5. Frigate is at 93% of its memory limit

**4,764 Mi against a 5 Gi limit**, with 7 cameras at 5 fps, 83 GB of recordings, and
`semantic_search` running a jinav2 index. One more camera or a reindex OOMKills the NVR. The node
is at 16 GB / 66% of 24 GB, so there is headroom.

Do: raise the limit to 6–7 Gi in `hawksnest-automation/kustomize/base/frigate/deployment.yaml`.
**Do not add a liveness probe** — its absence is deliberate and documented (it avoids an inescapable
kill-loop on slow camera init). Add a memory alert instead.

### 6. Pin floating image tags; give zwave-js-ui a probe

`zwave-js-ui` owns the Z-Wave radio for three Schlage deadbolts and runs on **`:latest`**, with
`privileged: true`, and **no liveness or readiness probe at all**. Frigate and ring-mqtt each omit a
liveness probe *with a comment explaining why*; zwave's absence is an oversight, not a decision. A
wedged daemon keeps its Service endpoint and HA keeps seeing the websocket as up.

`home-assistant:stable` is likewise unpinned (currently 2026.6.4) on the stack that controls the
locks and alarm panel — while the Frigate manifest carries a good comment about why *it* refuses to
float.

Do: pin `zwave-js-ui` to `11.22.0` (the running version) and `home-assistant` to `2026.6.4`. Add a
readiness probe to zwave on its websocket port. If liveness stays off, write down why.

### 7. Delete the dead camera screen

`android/app/src/main/java/com/hawksnest/ui/cameras/CamerasScreen.kt` is a Phase 0 placeholder
referenced by no file; `Screen.Cameras` in `ui/navigation/Screen.kt` is registered in no
`composable {}` block. Cameras live on Home instead.

Do: delete both, and the `Screen.Cameras` entry. Verify
`grep -rn "CamerasScreen\|Screen.Cameras" android/app/src` returns nothing.

---

## P1 — proof and process

### 8. 🧑 On-device push verification

Open since 2026-07-16 and the reason `V1.md` Gate 3 cannot close. Code is complete and unit-tested;
the runtime is not. Roughly 20 minutes with the phone.

Do: enable push in Settings (grant `POST_NOTIFICATIONS`, accept the battery-optimization
exemption). **Close the app entirely.** Ring the doorbell. Confirm: notification arrives, big-picture
snapshot renders, tap deep-links to the camera lightbox. Then reboot the phone and repeat without
opening the app (exercises `BootReceiver`). Then leave it overnight and confirm delivery still works
in the morning (exercises the 75 s read timeout and 2 s→60 s backoff in `NtfyPushService`).

Record the result in `android/README.md` — replace the "push on-device verify pending" status.

### 9. 🧑 Camera on-device pass

Open since 2026-07-16. Run `docs/CAMERA-SMOKE.md` end to end and record the outcome in-repo (a dated
results block in that file is fine). The checklist contains an unanswered question — whether a PTZ
press moves continuously or one step — answer it while the hardware is in front of you and write the
answer into `ARCHITECTURE.md`.

This is the only coverage the live camera pipeline will ever get: the mock serves no `web_rtc`
camera, so WebRTC/go2rtc/RTSP are unreachable headless by construction.

### 10. `CameraPlayerViewModel` tests

`android/app/src/main/java/com/hawksnest/ui/cameras/CameraPlayerViewModel.kt` (365 lines) is the
most intricate untested unit in the app. Its `resolveRingClip` / `awaitRecordingUrl` state machine
is timeout-driven (`RING_CLIP_TIMEOUT_MS = 20_000`) and its own comments call it subtle.

Cover: Idle→Resolving→Ready happy path; the 20 s timeout landing in Failed **rather than falling
back** to a possibly-stale published URL (this is deliberate — do not "fix" it); the
signature-expiry refetch path (Ring URLs expire ~15 min, refetched 60 s early via `timelineNonce`);
and one expired-signature refetch triggered by an ExoPlayer error.

Follow the existing patterns in `app/src/test` — 56 classes there, `TestEntities.kt` has helpers.

### 11. Camera ladder step-down test

`ui/cameras/CameraPlayer.kt` (784 lines) holds a tri-state interlock the docs call load-bearing:
while go2rtc's stream list is in flight, both WebRTC arms must hold **and** `wantsHls` must not
resolve, because resolving HLS early calls `camera/stream`, which wakes a battery camera's pipeline.
Nothing tests it. `Go2rtcStreamsTest` and `RtspHealthTest` cover only the circuit breakers.

Cover: a pending stream list holds HLS; and the full walk
`rtspFailed → go2rtc → go2rtcFailed → WebRTC → webRtcFailed → HLS → MJPEG → snapshot`. Extract the
tier-selection logic into a pure function in `core/logic/` if that is what it takes — that is the
pattern the rest of the app already follows.

### 12. Compose behaviour tests for the premium controls

~60 composables, zero behaviour tests. The gesture *math* is tested (`LightFeelTest`,
`ThermostatTest`, `LockStateTest`); the wiring from gesture to service call is not. PR #108 fixed a
bug in exactly that gap ("never guess a toggle's direction").

Add `createComposeRule` tests for: `LightPillar` drag → `dimCommit` with the right value;
`SlideToAct` requiring a full traverse and not firing on a partial drag; `LockVault` staying pending
until echo (**non-optimistic — this is a security invariant, assert it**); `RockerSwitch` sending the
correct direction for each half; `ThermostatDial` arc → setpoint; `ArmSegments` mode selection.

### 13. Gate `deploy.yml` on CI

`.github/workflows/deploy.yml` triggers on push to `main` with a path filter and races `ci.yml`. A
red typecheck, test, e2e or kubeconform does not stop the rollout.

Do: convert to `workflow_run` after `ci.yml` completes successfully on `main`. Keep the
`workflow_dispatch` escape hatch. **Keep it off `pull_request`** — it runs on a self-hosted runner on
the prod host, and the repo is public (suite invariant #7).

### 14. Assert `/go2rtc/` in `deploy.test.ts`

`src/__tests__/deploy.test.ts` is the self-declared spec for deploy changes and asserts
`/ring-timeline/` but not `/go2rtc/` — the route the entire top live-video tier and two-way Talk
depend on. Add it, plus the `map $http_upgrade` block, resource limits, replica count, probe paths,
and `imagePullPolicy`, following the existing assertions in that file.

### 15. Decide whether Sift should gate ✅ *partly done — PR #111*

**The version-scheme half is done.** `android-ci.yml` no longer stamps
`VERSION_NAME: 0.1.${run_number}`; it inherits the `1.0.0` default from `build.gradle.kts`, which is
the same line `android-release.yml` greps for major.minor.

**The Sift half turned out to be a non-problem.** The audit (and `V1.md`, and `ROADMAP.md`) said the
job was permanently red on an AGP 8.5.0 vs 9.1.1 mismatch. It isn't — Sift was bumped to 9.1.1 in
its own PR #2 and the job has been green since; run `30712842185` shows the composite build
resolving and `HawksnestDesignSlopTest` running.

What's actually left is a smaller decision: `continue-on-error: true` means the audit reports but
gates nothing, so 1.0 bar item #7 ("baselines gating, or job deleted") is still unsatisfied. Now
that it works, either drop `continue-on-error` and let new slop fail CI, or write down that it is
deliberately advisory. Note the baseline in `android/app/.sift/baseline.json` already means it only
ever surfaces *new* slop, so making it gating is less disruptive than it sounds.

### 16. Broaden the Android instrumented suite

`app/src/androidTest` has three test classes. `mock-ha/`'s `/__scenario` API supports `disconnect`,
`service-outcome` (`confirm|jammed|reject|silent`), `stream-outcome`, and scenarios `default` /
`lock-jam` / `bad-token` / `ring-camera`. The **web** Playwright suite uses all of it —
`e2e/ha/lock-pending.spec.ts` alone has 5 tests. Android uses almost none, on the platform where
these flows actually get used.

Add, mirroring the web specs: lock pending/jam/rejected, the offline grace-window transition
(`disconnect` → 120 s → full offline, security states masked immediately), doorbell notification
deep-link, and camera open. Runner exists: `scripts/android-emulator-test.sh` (needs `/dev/kvm`).
**No workflow invokes it today** — wire it into `android-ci.yml` if the runner has KVM, or onto the
weekly cron if not.

### 17. DataStore backup-exclusion test

`res/xml/data_extraction_rules.xml` and `backup_rules.xml` exclude
`datastore/hawksnest_prefs.preferences_pb` **by path**. That file currently holds the
Keystore-wrapped HA token and the RTSP camera password. Add a second DataStore for anything
sensitive and it is cloud-backed-up by default with no warning.

Do: a unit test asserting every `preferencesDataStore(name = …)` in the source tree appears in both
XML rule files. Parse the sources with a regex; this is cheap and makes the invariant self-defending.

### 18. Hygiene

- Close **PR #17** (`claude/hawksnest-ring-alarm-display-9t5as3`, open since 2026-06-27) — `V1.md`
  says it is superseded by `android-release.yml`.
- **PRs #83 and #105 are overlapping fixes for the same widget-wedging bug.** Pick one, close the
  other, before either merges.
- Merge or close the four dependabot PRs (#77 open since 2026-07-18, #88, #109, plus gradle).
- Prune ~20 dead `claude/*` remote branches from 2026-06-25→07-01.

`gh` is at `C:\Program Files\GitHub CLI\gh.exe` (may not be on PATH in a fresh shell), authenticated
as CDRaab01.

---

## P2 — features

Full rationale and sizing in `AUDIT-2026-08.md` §7. Suggested order, each independently shippable:

1. **App shortcuts** (S) — `shortcuts.xml` does not exist at all. "Lock front door", "Arm away",
   "Front camera". Services already wired through `ControlGate`. Highest value-per-hour on the list.
2. **Theme picker** (S) — `ui/theme/Theme.kt:69` already takes `darkTheme: Boolean =
   isSystemInDarkTheme()` as a parameter, and `LightColors` / `lightPulseColors()` already exist.
   Store a tri-state (Dark/Light/System) in `util/DevicePrefsStore.kt` and pass it in. Web already
   has this exact control in Settings; closes suite 1.0 bar #3.
3. **Crash reporting (GlitchTip)** (S) — ROADMAP2 T1 #7. Nothing exists on either platform today.
   **Pull this forward ahead of anything else shipping to the phone** — otherwise every item below
   ships blind.
4. **Quick Settings tiles** (M) — alarm and All Lock, reachable from the lock screen. A QS tile must
   honour the same non-optimistic contract as `LockVault`: show pending until HA confirms.
5. **Personalization editor** (M) — the largest real parity gap. Web has `/customize` with dnd-kit
   reorder / pin / hide; `util/DevicePrefsStore.kt` persists only `hidden_entities` and
   `entity_renames`, with no pin and no order. `config/Favorites.kt:5` has promised this since
   Phase 2. Add an `order` key and reuse the web's prefs model.
6. **Notification quick-actions** (M) — doorbell → View / Talk, alarm → Disarm, camera-object → View
   clip. Same non-optimism care as #4.
7. **Onboarding + what's-new card** (M) — suite 1.0 bar #1 and #8, both zero. First launch is a bare
   Settings form with no explanation of what a long-lived access token is or where to find it.
8. **Accessibility pass** (M) — 1.0 bar #11, no evidence it was ever run. Test at 1.3× font scale
   with TalkBack. The gesture-heavy controls (`LightPillar`, `SlideToAct`, `ThermostatDial`) are the
   likely failures; each needs a semantics action so it is operable without the gesture.
9. **Picture-in-Picture** (M) — the player already handles fullscreen and rotation without activity
   recreation, so the hard part is done.

Then §7.2: widget previews + themed icon, scene/script cards, a camera-snapshot widget, a
recent-activity strip on Home, kiosk mode, OAuth.

**Explicitly not recommended** (reasoning in §7.3, so these stay decided rather than forgotten):
Wear OS, Android Auto, NFC, geofencing, WorkManager background refresh, FCM.
