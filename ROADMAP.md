# ROADMAP.md — Hawksnest (departing-engineer assessment, 2026-07-03)

Hawksnest fronts the thing that unlocks the house's doors — its roadmap is security posture
first, then the one big missing capability, then polish. (Automation-side items — presence
auto-lock, Ratgdo garage, WLED — belong to `hawksnest-automation`; when those land, Hawksnest
mostly just renders the new entities via the existing domain-card mapping.)

## Security posture (do these before features)

1. **TLS on the proxy, then kill cleartext.** The Android `network_security_config.xml` is
   deliberately broad (documented in android/README.md) because the HA host may be a bare
   `100.x` IP. Front the proxy with TLS (Tailscale Serve gives free HTTPS at a MagicDNS name —
   the same mechanism Dragonfly's self-host source planned to use), standardize on the hostname,
   flip `cleartextTrafficPermitted="false"`. This closes the known, accepted Phase-0 hole.
2. **OAuth to HA** (web "next phases" list) — replace the long-lived token with HA's OAuth
   flow + refresh. Long-lived tokens in localStorage/CredentialStore are revocable but
   never-expiring; for a security surface that's the wrong default. Do web + Android together
   so the token story stays one story.

## The big missing capability

3. **Android Phase 4 — push notifications.** A doorbell app that only alerts while open isn't
   a doorbell app. The in-app banner already keys off the `_ding` binary sensor; push needs a
   server-side hook (HA automation → relay) + FCM, or self-hosted **ntfy** to skip Google
   infrastructure entirely (fits the suite's local-first bias; ntfy also generalizes to the
   suite-wide push channel in Dragonfly's roadmap). Scope it with the suite decision — build
   the relay once, not per-app.

## Improvements

4. **Camera pipeline test against real ring-mqtt** — the mock serves no `web_rtc` camera, so
   the live-video path (WebRTC web, LL-HLS Android) is only ever tested by hand. Even a
   documented manual smoke checklist per release ("live paints < 3 s, event scrub works,
   doorbell banner fires") would formalize what's currently tribal.
5. **Light theme** (web) — the tokens layer was built for it; the PULSE reference palette in
   the sibling Pulse repo already defines contrast-safe light variants to port.
6. **PWA update UX** — a "new version available, reload" toast when the service worker swaps
   the shell; silent updates occasionally strand a stale tab on the wall tablet use-case.
7. **Wall-tablet/kiosk mode** — if a mounted tablet becomes real: no-sleep, auto-reconnect
   aggressiveness, larger touch targets on the alarm panel. Cheap once light theme exists.

## Explicitly not worth it

- Embedding a VPN — the Tailscale sidecar model is documented and right (one VPN per Android
  device; don't fight the platform).
- Optimistic lock UI — the pending-until-HA-confirms behavior is a deliberate security
  decision, not lag to fix.
- Frigate NVR support beyond the existing seam — the code path exists (`cameraEvents.ts`) but
  buying/running Frigate hardware is a household decision, not an engineering gap.
