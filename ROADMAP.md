# ROADMAP.md — Hawksnest (departing-engineer assessment, 2026-07-03)

Hawksnest fronts the thing that unlocks the house's doors — its roadmap is security posture
first, then the one big missing capability, then polish. (Automation-side items — presence
auto-lock, Ratgdo garage, WLED — belong to `hawksnest-automation`; when those land, Hawksnest
mostly just renders the new entities via the existing domain-card mapping.)

## Road to 1.0 (suite pivot, 2026-07-13)

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code). Hawksnest is ahead of
the pack: **[V1.md](V1.md) is the live, sequenced 1.0 plan** and most of this file's items shipped
through its gates (marked ✓ below — this file stays as the original backlog record). What remains
is the V1.md merge train in its stated order: ntfy backend prod-apply → on-device push smoke →
Android push PR → **the 1.0 version bump last**. v1.1 holds OAuth (#2) and wall-tablet kiosk
mode (#7).

## Security posture (do these before features)

1. ✓ **TLS on the proxy, then kill cleartext — DONE** (V1.md Gate 2: Tailscale Serve `:8443` +
   nginx XFF-clear, PR #63; `cleartextTrafficPermitted="false"`, PR #64; token Keystore-wrapped
   + backup-excluded, PR #62). The Phase-0 hole is closed.
2. **OAuth to HA — deferred to v1.1** (owner decision, V1.md): with TLS done and the token
   Keystore-wrapped, the revocable LLAT doesn't block 1.0. Still the right v1.1 headline —
   do web + Android together so the token story stays one story.

## The big missing capability

3. **Android push notifications — ✓ code-complete via ntfy** (decided 2026-07-12: ntfy, not
   FCM; V1.md Gate 3): server side in hawksnest-automation #15, client `push/` foreground
   service in Hawksnest #66. **Gated operator steps remain** — deploy ntfy to prod, apply the
   HA automations, and the on-device smoke (delivery with the app closed) — only then is push
   proven. ntfy generalizes to the suite-wide push channel (Dragonfly roadmap / host Tier W2).

## Improvements

4. ✓ **Camera live-path smoke checklist — DONE** (`docs/CAMERA-SMOKE.md` → see V1.md Gate 4,
   PR #65) — the manual checklist formalizes what was tribal.
5. ✓ **Light theme — SHIPPED** (V1.md Gate 4, PR #65): `:root.light` tokens + Settings →
   Appearance (Dark/Light/System).
6. ✓ **PWA update UX — SHIPPED** (V1.md Gate 4, PR #65): `registerType:"prompt"` +
   `UpdateToast` (`useRegisterSW`), exactly for the wall-tablet stale-shell case.
7. **Wall-tablet/kiosk mode** (→ v1.1) — if a mounted tablet becomes real: no-sleep, auto-reconnect
   aggressiveness, larger touch targets on the alarm panel. Cheap once light theme exists.

## Explicitly not worth it

- Embedding a VPN — the Tailscale sidecar model is documented and right (one VPN per Android
  device; don't fight the platform).
- Optimistic lock UI — the pending-until-HA-confirms behavior is a deliberate security
  decision, not lag to fix.
- Frigate NVR support beyond the existing seam — the code path exists (`cameraEvents.ts`) but
  buying/running Frigate hardware is a household decision, not an engineering gap.
