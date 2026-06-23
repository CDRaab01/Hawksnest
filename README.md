# Hawksnest

A custom, opinionated web frontend for **Home Assistant** that replaces the stock HA dashboard
with a polished, app-like experience modeled on the **Spotter** design language (the "PULSE"
system). Home Assistant stays the backend/brain; Hawksnest is a presentation layer over HA's
WebSocket + REST APIs.

> **Status: Phase 2 — blended layout on fixtures.** The chosen design (an area-first hub with
> comfortable Spotter-style cards and compact read-only tiles) is now the real app, fed through an
> entity-store seam. It runs on **fixture data only** — the live HA WebSocket connection is the
> next phase (`src/store/haSource.ts` is a stub). See `CLAUDE.md` for the full spec.

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
src/store/                Zustand store + Source seam (fixtureSource now, haSource = Phase 1)
src/components/           PULSE primitives + EntityCard, AreaCard, ConnectionPill
src/cards/                domain cards (Lock, Camera, BinarySensor, Light, Alarm, Generic)
src/screens/              Home, Area detail, Settings
```

## Next phases

Live HA WebSocket connection + registries (`haSource`), optimistic-reconcile control wiring,
personalization editor, entity detail/history, PWA/service worker, OAuth, light theme. See
`CLAUDE.md`.
