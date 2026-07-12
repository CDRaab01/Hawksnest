# Hawksnest

A custom, opinionated web frontend for **Home Assistant** that replaces the stock HA dashboard
with a polished, app-like experience modeled on the **Spotter** design language (the "PULSE"
system). Home Assistant stays the backend/brain; Hawksnest is a presentation layer over HA's
WebSocket + REST APIs.

> **Status: Phase 4 — detail, history, more domains, drag-and-drop, PWA.** On top of the live HA
> connection (Phase 1), the blended area-hub layout (Phase 2), and the personalization editor
> (Phase 3 — pin/unpin, reorder, hide), Phase 4 adds: **entity detail** screens (tap any card) with
> a **state-history chart** (6h/24h/7d); **first-class cards** for cover, climate, media_player, and
> fan; **drag-and-drop** favorites reordering in Customize; and an installable **PWA** (offline app
> shell). With no token saved it falls back to demo fixtures.

### Install as an app (PWA)

Hawksnest is an installable PWA — open it in a browser and choose **Install / Add to Home Screen**.
The service worker precaches only the app shell; **it never caches anything under `/api`** (the live
HA WebSocket/REST surface), and the HA token lives in `localStorage` and is never touched by the
worker. Offline, the shell loads and the app shows its Offline/Demo state rather than stale data.

## Connecting to Home Assistant

Hawksnest is built to run as a pod in the same k3s cluster as HA, where its nginx serves the SPA
**and reverse-proxies `/api` to HA** — so the browser is same-origin (no CORS, no hardcoded IP).
See [`deploy/README.md`](deploy/README.md) for the k3s deployment.

1. In Home Assistant: profile → **Long-lived access tokens** → create one.
2. In Hawksnest: **Settings** → the URL defaults to this site (the proxy); paste the token →
   **Connect**. The token is stored locally on the device; **Disconnect** clears it. You can also
   point the URL directly at HA (e.g. `http://192.168.4.34:8123`).
3. The header pill shows `Connected` / `Reconnecting` / `Offline` / `Demo data`.

### Reaching home over Tailscale (Android)

The Android app reaches Home Assistant over **Tailscale** rather than exposing HA to the internet —
Hawksnest is *not* a VPN itself, it rides the tunnel the official Tailscale app provides on the
device (the "sidecar" model; only one VPN can be active on Android, so embedding a second client
isn't the right approach).

1. Install the **[Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn)** app
   and sign in to the same tailnet as your HA host / Hawksnest proxy. (Add the HA box to the tailnet,
   or run a [subnet router](https://tailscale.com/kb/1019/subnets) so its LAN IP is routable.)
2. In Hawksnest: **Settings → Tailscale** — tap **Open Tailscale** to install/launch it, set the
   **Base URL** above to your tailnet host (a MagicDNS `…ts.net` name or a `100.x` address), then
   **Test reachability** to confirm the tunnel routes to the host before chasing token errors.
3. Cleartext HTTP to tailnet hosts is allowed by `network_security_config.xml`; front the proxy with
   TLS and you can turn cleartext off entirely. Set `ha.url=` in `android/local.properties` to
   prefill the URL into debug builds.

### Dev
`npm run dev` proxies `/api` to `HA_PROXY_TARGET` (default `http://192.168.4.34:8123`), so the app
is same-origin locally too. See `deploy/README.md`.

## What's here

The real blended UI (dark-only), all rendering the owner's "Security" scene with **resolved
labels** (HA's raw "Lock Current status …" → "Front Door"):

- **Home** (`/`) — pinned favorites (large cards) above an **area hub** (`src/config/favorites.ts`).
- **Cameras** — a **Ring-style player** (tap any camera): on-demand low-latency live
  (**WebRTC** via `camera/webrtc/offer` → HLS → MJPEG → snapshot), a scrubbable **timeline** of
  recorded events, an in-player camera switcher, and transport (prev/next event, play/pause,
  snap-to-Live). Plus an app-wide **doorbell banner** that fires on a camera's `_ding` and opens its
  live view. The camera backend is **ring-mqtt** (Ring devices over MQTT + go2rtc): its split
  entities (`_live`/`_snapshot`/`_event` + event-selector/ding/motion) are collapsed into one
  logical camera (`src/lib/cameraModel.ts`), and recorded playback uses the **event selector**
  (last ~5 events, Ring Protect). **demo mode** runs the whole player against a bundled clip +
  synthesized events with no backend (`src/components/camera/`, `src/lib/{cameraModel,ringEvents,
  doorbell}.ts`). (Frigate's continuous-VOD path is also supported via `src/lib/cameraEvents.ts`.)
- **Area detail** (`/area/:area`) — **mixed density**: camera spans full width, controls render
  comfortable, read-only sensors render compact (`src/lib/density.ts`).
- **Settings** (`/settings`) — connection status + the "Connect to Home Assistant" form, plus a
  **Personalization** link into the Customize editor.
- **Customize** (`/customize`) — reorder/unpin the Home favorites and pin/hide any device. Edits
  write through to localStorage immediately (no explicit save); "Reset to defaults" forgets them.

Data flows through `src/store/` (Zustand): a `Source` populates the store
(`fixtureSource` now, `haSource` later) and screens read it via selector hooks. The
**label/icon resolution layer** (`src/lib/resolve.ts` + `src/config/overrides.ts`) and the
**domain→card mapping** (`src/lib/cards.ts`, never throws) are shared by every screen.

## Stack

React + TypeScript (Vite), Tailwind (PULSE tokens), Lucide icons, `react-router-dom`. Fonts are
self-hosted via Fontsource (Space Grotesk, Inter, JetBrains Mono).

## Develop

```bash
npm install
npm run dev        # http://localhost:5173
npm run typecheck  # tsc --noEmit (strict)
npm run lint       # eslint
npm run test       # vitest (screens, stores, mock-ha protocol, deploy-artifact checks)
npm run build      # tsc -b && vite build
npm run test:e2e   # Playwright end-to-end (auto-starts the mock HA server + dev server)
```

The test suite covers each screen functionally (Home, Area, Entity, Settings, Customize) plus
the connection/personalization stores, and `src/__tests__/deploy.test.ts` asserts the deploy
contract (nginx same-origin `/api` proxy + SPA fallback, Dockerfile, NodePort 30080). CI
(`.github/workflows/ci.yml`) additionally schema-validates the k8s manifests with
`kustomize build | kubeconform`.

### End-to-end tests (`e2e/` + `mock-ha/`)

Playwright drives the real app in Chromium against two backends:

- **Demo mode** (`e2e/demo/`) — no backend; navigation, the Customize editor (pin/hide/reorder),
  and the camera-player transport.
- **Live `haSource`** (`e2e/ha/`) — the app connects to **`mock-ha/`**, a standalone server that
  speaks the real `home-assistant-js-websocket` protocol with **scriptable** scenarios. This covers
  the integration seam an emulator can't: connect/auth, live state reconcile, reconnect, the
  doorbell banner, and the **security-critical lock** flow — pending→confirmed, a jam, and a
  rejected call — all **without touching a real lock**. The mock is a separate process so Android
  instrumented tests can point at the same server (see [`mock-ha/README.md`](mock-ha/README.md)) —
  run them on a headless emulator with `bash scripts/android-emulator-test.sh` (details in
  [`android/README.md`](android/README.md)).

`npm run test:e2e` starts both servers automatically. In the cloud sandbox it uses the
pre-installed Chromium; in CI the advisory `e2e` job installs Playwright's managed browser.

### Camera live path (manual)

The one seam automation can't reach — WebRTC (web) / LL-HLS (Android) live playback against real
go2rtc — has a per-release smoke checklist in [`docs/CAMERA-SMOKE.md`](docs/CAMERA-SMOKE.md). Run it
before releasing anything that touched the camera stack or the nginx camera proxy.

## Layout

```
src/theme/tokens.css      PULSE tokens (CSS variables) — ported from Spotter ui/theme
tailwind.config.ts        tokens mapped to Tailwind utilities (no raw hex in components)
src/lib/ha.ts             HA entity types (HassEntity-compatible) + helpers
src/lib/resolve.ts        label/icon resolution chain (override → friendly_name → prettified id)
src/lib/cards.ts          domain → card mapping (unknown → GenericCard, never throws)
src/lib/areas.ts          group entities by area registry
src/lib/density.ts        comfortable (controls) vs compact (read-only) vs feature (camera)
src/config/overrides.ts   per-entity label/icon overrides (seeded from the screenshot)
src/config/favorites.ts   default pinned Home entities (seed; user edits live in prefsStore)
src/store/preferences.ts  personalization persistence (localStorage; mirrors credentials.ts)
src/store/prefsStore.ts   Zustand store for pins/hides/order + selector hooks (useFavorites…)
src/fixtures/entities.ts  demo entities + area registry (behind fixtureSource)
src/store/                Zustand store + Source seam (fixtureSource demo / haSource live WS)
src/store/ha/registry.ts  resolve entity→area from HA's area/entity/device registries
src/components/           PULSE primitives + EntityCard, AreaCard, ConnectionPill
src/cards/                domain cards (Lock, Camera, BinarySensor, Light, Alarm, Generic)
src/screens/              Home, Area detail, Settings
```

## Controls

Card actions call HA services through the active source (`src/store/connection.ts` → the source's
`callService`): the live source forwards `call_service` over the WebSocket and HA's state echo
reconciles the store; the demo source simulates it locally. Lock/unlock, light on/off + brightness,
and alarm arm/disarm are wired. Locks are excluded from optimistic UI — they show a pending state
until HA confirms.

## Next phases

OAuth (replacing the long-lived token), light theme, live camera streams, and more first-class
domains. (Phase 4 delivered entity detail + history, cover/climate/media_player/fan cards,
drag-and-drop favorites reordering, and the installable PWA.)
