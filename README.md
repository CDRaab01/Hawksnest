# Hawksnest

A custom, opinionated web frontend for **Home Assistant** that replaces the stock HA dashboard
with a polished, app-like experience modeled on the **Spotter** design language (the "PULSE"
system). Home Assistant stays the backend/brain; Hawksnest is a presentation layer over HA's
WebSocket + REST APIs.

> **Status: Phase 1 — live Home Assistant connection.** The chosen design (an area-first hub with
> comfortable Spotter-style cards and compact read-only tiles) runs on a real HA WebSocket
> connection: enter your HA URL + a long-lived token in **Settings** and it streams live entity
> states and resolves areas from the registries. With no token saved it falls back to demo
> fixtures. See `CLAUDE.md` for the full spec.

## Connecting to Home Assistant

1. In Home Assistant: profile → **Long-lived access tokens** → create one.
2. In Hawksnest: **Settings** → enter your HA URL (e.g. `http://192.168.4.34:8123`) + the token →
   **Connect**. The token is stored locally on the device; **Disconnect** clears it.
3. The header pill shows `Connected` / `Reconnecting` / `Offline` / `Demo data`.

## What's here

The real blended UI (dark-only), all rendering the owner's "Security" scene with **resolved
labels** (HA's raw "Lock Current status …" → "Front Door"):

- **Home** (`/`) — pinned favorites (large cards) above an **area hub** (`src/config/favorites.ts`).
- **Area detail** (`/area/:area`) — **mixed density**: camera spans full width, controls render
  comfortable, read-only sensors render compact (`src/lib/density.ts`).
- **Settings** (`/settings`) — connection status + a stub "Connect to Home Assistant" form (the
  Phase 1 seam).

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
npm run test       # vitest
npm run build      # tsc -b && vite build
```

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
src/config/favorites.ts   pinned Home entities (static; editor is Phase 3)
src/fixtures/entities.ts  demo entities + area registry (behind fixtureSource)
src/store/                Zustand store + Source seam (fixtureSource demo / haSource live WS)
src/store/ha/registry.ts  resolve entity→area from HA's area/entity/device registries
src/components/           PULSE primitives + EntityCard, AreaCard, ConnectionPill
src/cards/                domain cards (Lock, Camera, BinarySensor, Light, Alarm, Generic)
src/screens/              Home, Area detail, Settings
```

## Next phases

Service-call wiring so card controls drive HA (lock/unlock, light on/off + brightness) with
optimistic-reconcile (locks excepted), personalization editor, entity detail/history,
PWA/service worker, OAuth (replacing the long-lived token), light theme. See `CLAUDE.md`.
