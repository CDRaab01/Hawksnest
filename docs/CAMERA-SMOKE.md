# Camera live-path smoke checklist

The camera **live** path is the one seam with no automated coverage: `mock-ha/` speaks the HA
websocket protocol but serves no real `web_rtc`/`go2rtc` stream, so WebRTC negotiation (web) and
LL-HLS playback (Android) can only be exercised against real hardware. Everything *around* live —
tile/lightbox layout, the doorbell banner, lock flows, reconnect — is covered by vitest + Playwright
+ the Android instrumented tests. This checklist is the manual gate for what they can't reach.

**Run it before cutting a release that touched anything under** `src/components/camera/`,
`src/lib/cameraModel.ts`, `src/components/{LivePlayer,WebRtcPlayer,HlsPlayer}.tsx`, the Android
camera stack, or the nginx camera proxy in `deploy/nginx.conf`. It takes ~5 minutes against the
live Ring/go2rtc backend (a real doorbell + one other camera is enough).

## Preconditions

- Reaching HA over the TLS front (`https://<host>.ts.net:8443`) — the same path phones use, so XFF
  stripping in nginx is exercised (a mis-set `X-Forwarded-For` 400s camera frames — see CLAUDE.md).
- ring-mqtt + embedded go2rtc up in the HA namespace; at least one doorbell (`_ding`) and one
  non-doorbell camera present.

## Web (WebRTC)

- [ ] **Live paints < 3 s.** Open a camera tile → the live frame appears within ~3 seconds; the
      "Connecting…" overlay clears (no indefinite spinner).
- [ ] **Lightbox live.** Tap the tile → full-screen player negotiates WebRTC and shows live video;
      the tile→player View Transition morphs the named pair (rest of the page pinned).
- [ ] **Recorded scrub.** Switch to the event selector → the last ~5 Ring events load; picking one
      plays that clip (recorded playback = event list, not continuous VOD).
- [ ] **Two cameras.** Open a second camera without closing the first → each negotiates its own
      stream; no black frame, no cross-wired video (the WebRTC factory is a singleton by design).
- [ ] **Reconnect.** Kill Wi-Fi briefly → the ConnectionPill goes stale, live drops gracefully
      (Offline state, not a crash); restore → live re-negotiates on reopen.
- [ ] **No console errors** beyond expected ICE churn.

## Android (LL-HLS)

- [ ] **Live paints < 3 s** on a cold app open of a camera.
- [ ] **Event scrub** works from the event selector (same last-~5-events model).
- [ ] **Backgrounding.** Home out mid-stream and return → live resumes without a stuck frame or an
      orphaned player holding the connection.

## Doorbell (both)

- [ ] **Ding banner fires.** Press the real doorbell → the in-app banner drops in within a couple of
      seconds; **View** opens that camera's live player.
- [ ] **Push fires** (once Gate 3 ships): with the app closed, the ntfy push arrives and tapping it
      deep-links to the camera.

## If something fails

- Camera frames 400 / never paint over the TLS path → suspect `X-Forwarded-For` leaking to HA;
  confirm nginx clears XFF/XFP for every HA-proxied `location` (deploy/README.md, CLAUDE.md invariant).
- Live spins forever but recorded works → go2rtc/WebRTC negotiation, not the app shell.
- Doorbell banner silent → check the `_ding` binary_sensor is arriving in the entity stream
  (`activeDoorbellPress` in `src/lib/doorbell.ts` keys off it).
