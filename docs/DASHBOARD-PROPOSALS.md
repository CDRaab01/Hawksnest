# DASHBOARD-PROPOSALS.md — what to take from *Material Clean* (2026-07-26)

A design review prompted by [jlnbln's "My Material Clean Dashboard"](https://jlnbln.gumroad.com/l/iusgs),
a free Home Assistant dashboard package. Nothing here is ported code — that product is a
`dashboard.yaml` plus ten HACS cards, and we own our renderer. What follows is the information
design worth stealing, sized against our own files.

Renders of every proposal: [`dashboard-proposals.html`](dashboard-proposals.html) (standalone, opens
in a browser; PULSE tokens and the real type stack are inlined so it renders identically anywhere).

## What the product actually is

Free (€0), functioning as a funnel for paid dashboard commissions. Ships `dashboard.yaml`, a
`material-clean.yaml` theme, helper/template-sensor includes, one automation, and `www/` image
assets — layered on button-card, auto-entities, layout-card, mini-graph-card, swipe-card,
my-slider-v2, card-mod, kiosk-mode, Bubble Card and Spook. Every visual is card-mod CSS injected
over Lovelace; the buyer hand-swaps entity IDs.

Its Home screen: a left sidebar (Home / Favourites / Security / Devices / Automations), a top chip
row, room tiles with per-room temperature, a Favourites row, weather + climate cards, and a
right-hand security column headed by a 0–100 "Security Score".

## Proposals

Ordered by value-per-effort. Sizes are relative, not estimates.

### 1. Security counts instead of the prose line — **S**

`components/SecurityStatusBar.tsx` builds its summary by joining *every* offender with `·`. One
offender reads well; five wrap to three lines and stop being glanceable — which is exactly when
glanceability matters. Replace with counts carrying denominators (`1/1 armed · 4/6 closed ·
1/2 locked · 5/6 online`), which hold their size regardless of how bad it gets. All four buckets
are already computed in that component's existing `useMemo`, so this is a render change, not a
logic change. The offender list moves one tap deeper.

**Deliberately not adopted: the headline score.** Material Clean's "62/100" is a template sensor.
Ours would have to respect the masking invariant — a single number can't represent an entity whose
state we've masked, so offline must *subtract* rather than drop silently out of the denominator.
Counts express that honestly; a score doesn't.

### 2. Favourites on the Dashboard — **S**

`config/favorites.ts` seeds locks + alarm, `store/prefsStore.ts` handles pin/reorder, and
`screens/CustomizeScreen.tsx` edits the list — but `screens/DashboardScreen.tsx` renders none of
it. The machinery exists and isn't wired to the landing screen. This is our gap, not a borrowed
idea.

### 3. Activity chip row — **M**

A thin ambient row above the fold. Derived client-side from the entity store; Material Clean needs
template sensors and a Spook dependency for the same numbers.

**Scoped to non-security domains only** (lights, media_player, climate, fan). The first draft of
this proposal duplicated the counts card — "2 doors open" is just `4/6 closed` restated one card
higher. The two elements must stay disjoint: **the counts card owns security posture, the chips own
everything that isn't security.** A useful consequence: no chip is ever `streak`, so attention lives
in exactly one place on the screen and a glance that finds no orange is genuinely reassuring.

Chips sit *below* the security card. Above it would put comfort state in the most prominent slot on
a security-forward screen.

### 4. Weather card — **M**

`weather` is absent from the domain map in `lib/cards.ts`, so a weather entity currently falls
through to `GenericCard` — a label and a raw state string. Needs a new card, a condition→backdrop
mapping, and a Compose equivalent for Android.

### 5. Climate with history — **M**

`cards/ClimateCard.tsx` has no trend. `components/Sparkline.tsx` already exists and
`screens/HistoryScreen.tsx` already fetches the series; the work is wiring a history fetch into a
card that doesn't currently do one. The segmented mode pill (Heat / Cool / Off) also gives the alarm
a compact desktop control, where three 64px discs are more thumb than a mouse needs.

### 6. Desktop layout — **L**

We serve from an nginx pod, so people open Hawksnest on a laptop and get a phone screen stretched
wide; there are 13 responsive breakpoint usages in all of `src/`. The IA is already right — the
proposed sidebar is `components/BottomBar.tsx`'s exact five routes re-laid-out at `lg:`. Cheap to
draw, broad to land: every screen needs a wide-viewport pass and the Playwright suite is written
against mobile layout.

## What doesn't transfer

Their fragility is ten pinned community cards that HA upgrades can break; ours is the HA WebSocket
API, which is far more stable. And several things they structurally cannot do: non-optimistic locks,
the honest 120s grace window in `lib/offline.ts` (Lovelace shows stale state indefinitely and calls
it live), the WebRTC camera wall, the native Android app + widgets, and the Keystore-encrypted
token.

The one place they beat us outside the UI is packaging — a step-by-step README that takes a stranger
from zero to running.

## Status

Proposals only. Nothing here is implemented; none of it alters architecture, so `ARCHITECTURE.md` is
untouched. Sequencing suggestion: 1 and 2 first (both small, both self-contained), then 3.
