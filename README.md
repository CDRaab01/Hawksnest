# Hawksnest

A custom, opinionated web frontend for **Home Assistant** that replaces the stock HA dashboard
with a polished, app-like experience modeled on the **Spotter** design language (the "PULSE"
system). Home Assistant stays the backend/brain; Hawksnest is a presentation layer over HA's
WebSocket + REST APIs.

> **Status: Phase 0 — design exploration.** This repo currently contains three static, clickable
> design directions built on ported PULSE tokens with **fixture data only** (no live HA). The goal
> is to pick a direction before building the live connection. See `CLAUDE.md` for the full spec.

## Phase 0 — what's here

Three directions, all dark-only, all rendering the exact "Security" scene from the owner's stock-HA
screenshot so they can be compared against the "before":

- **A · Spotter-faithful list** (`/a`) — vertical sections, large comfortable cards.
- **B · Dense grid** (`/b`) — compact tiles, control-panel density.
- **C · Area-first hub** (`/c`) — area cards that drill into a detail view.

Everything routes off `/` (Overview). The **label-resolution layer** (`src/lib/resolve.ts` +
`src/config/overrides.ts`) is real and turns HA's raw "Lock Current status …" into "Front Door".

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
src/config/overrides.ts   per-entity label/icon overrides (seeded from the screenshot)
src/fixtures/entities.ts  Phase 0 fixture entities + area registry
src/components/           PULSE primitives (PanelCard, PulseButton, DataText, SectionHeader)
src/cards/                domain cards (Lock, Camera, BinarySensor, Light, Alarm, Generic)
src/mockups/              the three directions + Overview
```

## Not in Phase 0 (next phases)

Live HA WebSocket connection + registries, optimistic-reconcile control wiring, personalization,
entity detail/history, PWA/service worker, OAuth, light theme, remaining domains. See `CLAUDE.md`.
