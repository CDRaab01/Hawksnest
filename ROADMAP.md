# ROADMAP.md — Hawksnest (rewritten 2026-07-16: 1.0 is shipped — prove it, then say so)

Hawksnest fronts the thing that unlocks the house's doors — its roadmap stays security posture
first. (Automation-side items — presence auto-lock, Ratgdo garage, WLED — still belong to
`hawksnest-automation`; when those land, Hawksnest mostly just renders the new entities via the
existing domain-card mapping.)

## The headline: 1.0 has already shipped — the docs just haven't admitted it

In artifact terms, 1.0 is out: web `package.json` is `1.0.0` and CI has published Android
releases `android-v1.0.144` through `android-v1.0.157`. But this file's previous revision and
[V1.md](V1.md) still describe the version bump as pending. The suite 1.0 bar (host ROADMAP3)
includes **truthful docs** — Hawksnest currently fails its own bar on that line and no other.
This rewrite is the reconciliation: 1.0 is declared shipped; what remains is **proving two
subsystems** that are code-complete but unproven end-to-end, and closing the docs lag.

### Shipped (the recap — the build record lives in git history and V1.md's gates)

Everything the 2026-07-03 backlog and V1.md sequenced has landed: crash-safe controls with
honest pending state (web + Android, PRs #50/#51), TLS via Tailscale Serve `:8443` + nginx
XFF-clear + Android cleartext OFF (PRs #63/#64), the HA token Keystore-wrapped and
backup-excluded (PR #62), push code-complete via self-hosted ntfy (server: hawksnest-automation
#15; client: the `push/` foreground service, #66), the camera smoke checklist
(`docs/CAMERA-SMOKE.md`), light theme + PWA update toast (PR #65), the go2rtc-direct Android
live tier (PR #72), and the 1.0 version bump itself (V1.md item 11 — merged; the
`android-v1.0.x` release train is live).

## Prove it (what remains is proof and truth, not features)

- [ ] [H] **Push end-to-end proof.** Deploy ntfy to prod, apply the rest_command + doorbell/
  alarm automations to the *live* HA (docs/ntfy-push.md), and run the on-device smoke —
  delivery with the app closed. These are the V1.md Gate-3 operator steps: push is
  code-complete but unproven until a real ding lands on a phone with the app shut.
- [ ] [H] **Camera pipeline on-device pass** per `docs/CAMERA-SMOKE.md`. Live WebRTC/LL-HLS is
  hand-tested only, and the mock serves no real camera — the checklist exists precisely so
  this becomes a repeatable pass instead of tribal memory. Run it once, formally.
- [ ] **Sift design-audit CI job — decide whether it should gate.** ~~Red since the AGP bump~~ —
  **that was fixed**: `CDRaab01/Sift` moved to AGP 9.1.1 in its own PR #2, and the job has been
  green since (run `30712842185` shows the composite build resolving and `HawksnestDesignSlopTest`
  running). What's left is smaller: `continue-on-error: true` means the audit reports but gates
  nothing, so the 1.0 bar's "baselines gating, or job deleted" is still unsatisfied. Either drop
  `continue-on-error` — the baseline in `android/app/.sift/` means only *new* slop would fail — or
  record that it is deliberately advisory.
- [ ] **Docs pass — reconcile prose with the shipped android-v1.0.x reality.** This rewrite is
  most of it. Follow-up: V1.md needs its own one-line closure edit (its status prose still
  presents item 11 as the pending PR), plus a README sweep for any lingering pre-1.0 phrasing.

## v1.1 candidates

- [ ] **OAuth to HA** — replace the long-lived access token with HA's OAuth + refresh, web +
  Android together so the token story stays one story. Once in, the web token can finally
  leave localStorage too. The v1.1 headline.
- [ ] **Wall-tablet/kiosk mode** — if a mounted tablet becomes real: no-sleep, auto-reconnect
  aggressiveness, larger touch targets on the alarm panel. Cheap now that light theme exists.
- [x] **Two-way doorbell talk** — **SPIKE PASSED, and the feature was already built.**
  Confirmed on the hardware 2026-08-31 against the Reolink Video Doorbell WiFi (D340W), not
  ring-mqtt: the Ring doorbell was retired on 2026-08-30 and the Reolink is now the doorbell, so
  the original spike target no longer exists.

  Evidence, from go2rtc's own view of the stream while Talk was held. The camera advertises the
  ONVIF backchannel in its RTSP SDP —

      m=audio 0 RTP/AVP 0
      a=rtpmap:0 PCMU/8000
      a=sendonly

  — and go2rtc showed live `pcm_mulaw/8000` **senders** on that producer (12,960 and 49,920 bytes),
  i.e. microphone audio reaching the speaker. The camera's `GetAbility` also reports
  `talk: {permit: 4, ver: 1}`.

  No code was needed. `TalkButton` exists on both clients and its gate stopped being `isRing` on
  2026-08-05 — that change was made precisely because `isRing` describes where a camera's
  RECORDINGS live and says nothing about its speaker, and it had been excluding every Reolink from
  a feature they support. Once go2rtc served `front_door_reolink`, Talk was already reachable.

  The same backchannel carries Quick Reply, which is now on **both** clients (web gained it
  2026-08-31; Android had it already).

## Play fork program

Not a candidate. Hawksnest is LAN/tailnet-only by design and useless without the owner's Home
Assistant instance — the Play fork program covers Spotter/Plate/Cookbook only (host-level
`C:\Code\PLAY-FORKS.md`).

## Explicitly not worth it (unchanged — still true)

- Embedding a VPN — the Tailscale sidecar model is documented and right (one VPN per Android
  device; don't fight the platform).
- Optimistic lock UI — the pending-until-HA-confirms behavior is a deliberate security
  decision, not lag to fix.
- Frigate NVR support beyond the existing seam — the code path exists (`cameraEvents.ts`) but
  buying/running Frigate hardware is a household decision, not an engineering gap.
