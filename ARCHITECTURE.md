# ARCHITECTURE.md — Hawksnest (software-level)

How this repo is organized and why. Suite-level context: `C:\Code\ARCHITECTURE.md`. Working
instructions + production-breaking invariants: [CLAUDE.md](CLAUDE.md). Feature/dev/testing
overview: [README.md](README.md). Backlog: [ROADMAP.md](ROADMAP.md).

Hawksnest is the **odd one out** in the suite: a presentation layer over **Home Assistant**
(HA stays the brain), no backend of its own, no public ingress (LAN/Tailscale only), deliberately
excluded from the Dragonfly config broker and SSO (it authenticates directly to HA). It fronts
the thing that unlocks the house's doors — security posture outranks features here.

## System shape

```
Web SPA/PWA (React/TS, nginx pod) ──same-origin /api──▶ nginx ──proxy──▶ Home Assistant (k3s, WSL2)
Android app (Kotlin/Compose) ──Tailscale, long-lived token──────────────▶ HA WebSocket + REST
                                                    HA ⇄ ring-mqtt (+go2rtc) ⇄ Ring doorbell/cams
                                                    HA ⇄ Z-Wave JS (locks, etc.)
```

One repo, three deliverables:

## 1. Web SPA/PWA (`src/`)

React + TypeScript (Vite), Tailwind mapped onto PULSE CSS tokens (`src/theme/tokens.css` — a
**CSS port** of the design language, *not* the `design.pulse:pulse-ui` Gradle library; that's
Compose-only).

| Area | Responsibility |
|---|---|
| `src/lib/` | The domain kernel. `cameraModel.ts` collapses ring-mqtt's split entities (`_live`/`_snapshot`/`_event` + selectors/ding/motion) into one logical camera; `cards.ts` maps HA domains → card components (**must never throw** — unknown domains render `GenericCard`); `resolve.ts` centralizes label/icon resolution (per-entity overrides go in `src/config/overrides.ts`, never in components) |
| `src/store/` | Client state: HA WebSocket connection, auth, entity registry, reconnect logic. The entity sink dedupes the Ring-vs-ring-mqtt double exposure centrally (`src/lib/dedupe.ts`, platform map from the registry): when both integrations expose the same light, the ring-platform twin is dropped so every consumer sees one entity per physical device |
| `src/screens/` + `src/cards/` + `src/components/` | Presentation; no raw hex — PULSE tokens only. Loading states use the shared `Skeleton` (one hairline-strong shimmer sweep — camera first-frame decode, history fetch); the dashboard arm discs activate via a channel fill-sweep, still non-optimistic (the sweep follows HA's echo, pinned in tests) |
| `src/config/` | Entity/room overrides |
| `public/` + service worker (vite config) | PWA shell. **The SW never caches `/api` and never touches the HA token** — offline = shell + Offline/Demo state, never stale entity data |

Camera streaming: WebRTC negotiates over the existing `/api/websocket`; media flows UDP direct to
go2rtc. Recorded playback = the last ~5 Ring events via the event-selector entity (not continuous
VOD; a Frigate seam exists in `cameraEvents.ts`, unused).

## 2. Android app (`android/`, package `com.hawksnest`)

Kotlin/Compose, talks to HA directly over Tailscale with a long-lived token. Full guide:
`android/README.md`.

- `core/ha/` — HA WebSocket/REST client (the Kotlin analogue of the web store). **All user-facing
  control calls go through `ControlGate`** (via `ConnectionManager.control`): it is the crash-safety
  layer (a failed call becomes a message on the app-level snackbar, never an uncaught coroutine
  exception) and the honest-pending tracker (entity id held in `pendingControls` until HA echoes,
  the call fails, or a 30 s timeout reports "didn't respond"). Raw `callService` is reserved for
  screens that surface their own errors (lock keypad codes, Z-Wave maintenance).
- `core/logic/`, `core/automations/` — entity → domain-model mapping, automation surfaces.
  Includes the ring/ring-mqtt dedupe (`Dedupe.kt`, applied centrally at `HaSource`'s entity sink,
  mirroring the web) and the Devices sectioning model (`DeviceSections.kt`: per-room three-tier
  rhythm — FEATURED lock/climate/alarm cards, CONTROL rows with inline switches, READONLY rows).
- `ui/<feature>/` — home/rooms/area/devices/cameras/entity/history/automations/settings.
- **Devices v2** (`ui/devices/`): single-column list in the three-tier rhythm, PULSE segment
  chips (not stock M3), room summaries ("N devices · M on"), search, and long-press → rename/hide
  persisted in `util/DevicePrefsStore` (DataStore) with a hidden-devices shelf. Display names
  resolve rename → override → non-junk friendly_name → registry device name
  (`core/logic/Resolve.kt displayName`).
- **Control interaction model** (`ui/components/`): locks use `SlideToAct` — the drag is the
  confirmation, and the thumb holds a spinner until HA's echo (non-optimistic, per invariant 1).
  Lights/switches/fans render **optimistically** — the switch thumb follows the finger, the echo
  reconciles, and a failure snaps back (they are not security surfaces; the non-optimism invariant
  is locks/alarm only). Alarm segments are plain taps with per-segment pending spinners. Haptics
  route through the `Haptics` vocabulary (`rememberHaptics()`) — actuation tick, threshold buzz on
  the slide's commit point, reject buzz with the failure snackbar.
- Cleartext HTTP is **deliberately permitted** (`network_security_config.xml`): the HA host can
  be a bare `100.x` Tailscale IP, which a scoped domain-config cannot match. The fix is TLS on
  the proxy first (ROADMAP #1), then flip `cleartextTrafficPermitted="false"` — not a manifest
  tweak on its own.
- Suite membership: signed with the suite key (secrets are `HAWKSNEST_`-prefixed), released by
  `android-release.yml` on `android/**` pushes, tagged `android-vX.Y.Z` (clear of web `v*`).
  Managed by the Dragonfly hub for updates — but **no** SuiteConfigReader/AppAuth (nothing to
  broker; don't add them without a real reason).

## 3. Deployment (`deploy/`)

The web app ships as an nginx pod in the **same k3s cluster/namespace as HA itself** (cluster
owned by the sibling `hawksnest-automation` repo), NodePort 30080, exposed to LAN/Tailscale via
Windows portproxy scripts that run at logon (WSL IP changes each reboot).

nginx reverse-proxies `/api` to HA so the browser is same-origin. **Invariant: nginx must NOT
send `X-Forwarded-For` to HA** — with `use_x_forwarded_for` and an untrusted proxy IP, HA 400s
every request (this killed all camera frames once). Either keep XFF off (current) or send it AND
trust the flannel pod CIDR in HA — never half-do it.

`deploy.test.ts` asserts the deploy contract (nginx config, Dockerfile, NodePort) — **if you
change deploy files, that test is the spec**; update both together.

## Testing map (all real seams covered without real hardware)

- `npm run test` — vitest: screens, stores, protocol, `deploy.test.ts`.
- `npm run test:e2e` — Playwright against **`mock-ha/`**, a scriptable fake HA speaking the real
  WebSocket protocol (auth, reconnect, doorbell, lock pending/jam/rejected flows) with a
  `/__scenario` API. The same mock serves Android instrumented tests
  (`scripts/android-emulator-test.sh`, needs KVM).
- CI: `ci.yml` (typecheck/lint/test/build + kubeconform over the k8s manifests),
  `android-ci.yml` (unit tests + assembleDebug + the advisory **Sift** design audit — a sibling
  public repo checked out by CI; known to trip on the new Compose render, advisory-only).
- Known gap: the live camera pipeline (WebRTC web / LL-HLS Android) is only ever hand-tested —
  the mock serves no `web_rtc` camera. ROADMAP #4 wants at least a written per-release smoke
  checklist.

## Invariants (security-flavored — this app unlocks doors)

1. **Locks _and the alarm_ are non-optimistic UI** — pending until HA confirms, and a failed or
   silent call surfaces an error rather than doing nothing. Deliberate; the E2E suite pins it.
   Don't "fix" the lag. Web: the alarm arm/disarm behaviour is shared by the dashboard
   `SecurityStatusBar` and the `AlarmCard` via the `useAlarmControl` hook (tapped mode spins until
   HA reaches the requested state / `triggered`, a rejected call shows an error, and a safety-net
   timeout stops a spinner the panel never answers); `LockCard` carries the same pending/error +
   timeout contract. Android: the same contract lives in `ControlGate`, and locks render as
   `SlideToAct` — the pending wait *is* the thumb holding at the end of the track. The optimistic
   switches on lights/switches/fans are **not** a violation: the invariant covers security
   domains — locks and the alarm — only.
2. **Service worker: never cache `/api`, never touch the token.**
3. **nginx XFF rule** (above) — all-or-nothing.
4. Long-lived-token auth is the accepted Phase-0 posture; the upgrade path is TLS-then-OAuth
   (ROADMAP #1–2), in that order, web + Android together so the token story stays one story.
5. Unknown HA domains must render, not crash (`cards.ts` contract).

## Where to make common changes

- **New device type/domain**: `src/lib/cards.ts` mapping + a card component (web);
  `core/logic` + `ui/devices` (Android). Entity naming quirks → `config/overrides.ts`.
- **HA protocol behavior**: extend `mock-ha/` first, write the failing E2E, then implement.
- **Deploy changes**: `deploy/` + `deploy.test.ts` together.
- **Automation-side features** (new sensors, Ring/Z-Wave config): wrong repo — that's
  `hawksnest-automation`; Hawksnest usually just renders the new entities via the domain mapping.
