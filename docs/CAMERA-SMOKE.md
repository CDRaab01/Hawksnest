# Camera live-path smoke checklist

The camera **live** path is the one seam with no automated coverage: `mock-ha/` speaks the HA
websocket protocol but serves no real `web_rtc`/`go2rtc` stream, so WebRTC negotiation (web) and
LL-HLS playback (Android) can only be exercised against real hardware. Everything *around* live —
tile/lightbox layout, the doorbell banner, lock flows, reconnect — is covered by vitest + Playwright
+ the Android instrumented tests. This checklist is the manual gate for what they can't reach.

**Run it before cutting a release that touched anything under** `src/components/camera/`,
`src/lib/cameraModel.ts`, `src/lib/clipExport.ts` / `core/logic/ClipExport.kt`,
`src/components/{LivePlayer,WebRtcPlayer,HlsPlayer}.tsx`, the Android camera stack, or the nginx
camera proxy in `deploy/nginx.conf`. It takes ~5 minutes against the
live Ring/go2rtc backend (a real doorbell + one other camera is enough).

## Preconditions

- Reaching HA over the TLS front (`https://<host>.ts.net:8443`) — the same path phones use, so XFF
  stripping in nginx is exercised (a mis-set `X-Forwarded-For` 400s camera frames — see CLAUDE.md).
- ring-mqtt + embedded go2rtc up in the HA namespace; at least one doorbell (`_ding`) and one
  non-doorbell camera present.

## Web (WebRTC)

- [ ] **go2rtc-direct tier (once §7c `:8555` is up).** Open a ring camera → first frame in ~1–2 s
      (faster than the HA path). Confirm it's the go2rtc tier: `/go2rtc/api/streams` lists the
      camera, and the browser makes a `wss://…/go2rtc/api/ws?src=<base>` connection (devtools →
      Network → WS). With `:8555` **not** forwarded, it must fall back to the HA WebRTC tier within
      ~8 s and the session circuit-breaker then skips go2rtc for other cameras (no repeated stall).
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

## Camera movement (Reolink PTZ) — run on BOTH platforms

Only on the Reolink cameras (`big_room`, `first_floor_stairway`, `kitchen`). The pad is behind
the **Move** button in the player chrome, live view only.

- [ ] **The unanswered question: does a press move continuously or one step?** Hold a direction
      ~2 s. If the camera keeps moving the whole time and halts on release, presses are
      *continuous*; if it nudges once and stops by itself, they're *steps*. Either is fine —
      the UI is built for both — but **record the answer here** once it's known, since it
      decides whether a future repeat-while-held timer would help or double-fire.
- [ ] **Stop on release actually stops.** Hold, release, and confirm the lens is stationary
      within a beat. This is the invariant everything else protects.
- [ ] **Stop on leaving.** Start a move and immediately close the player (or background the
      app / switch tabs). The camera must not still be panning when you come back.
- [ ] **Zoom** (E1 Zooms only — `big_room`, `first_floor_stairway`): drag the slider, release →
      the image visibly zooms and the value settles to what the camera reports. `kitchen` is an
      E1 Pro and must show **no** zoom/focus controls at all.
- [ ] **Focus + autofocus.** Autofocus on → the focus slider is disabled. Turn it off → the
      slider enables and moving it visibly changes focus.
- [ ] **The stairway alias.** Confirm the pad appears on `first_floor_stairway` and moves *that*
      camera — its Reolink device is named `stairway`, so this is the case a naive
      name-derivation would have silently dropped.
- [ ] **Ring cameras show no Move button** (they can't move).
- [ ] **Presets.** None exist until one is saved in the Reolink app; after saving one, a
      Position control appears and recalling it slews the camera.

## Android (LL-HLS)

- [ ] **Live paints < 3 s** on a cold app open of a camera.
- [ ] **Event scrub** works from the event selector (same last-~5-events model).
- [ ] **Backgrounding.** Home out mid-stream and return → live resumes without a stuck frame or an
      orphaned player holding the connection.
- [ ] **Screen off stops the stream (Android).** Open a live camera, unmute, press power: sound
      stops at once. Wait 30 s, unlock → "Connecting…" then live again on the SAME tier (not a
      step-down to HLS/snapshot). Repeat from PiP. Proof it really stopped, over adb:
      `dumpsys batterystats --reset`, 10 min screen-off with a camera open, then
      `dumpsys batterystats com.hawksnest` shows no `AudioMix` wakelock and ~no Wi-Fi rx.
- [ ] **The HA socket sleeps with the app (Android).** Background the app for > 30 s and return:
      Home shows the dimmed "Reconnecting" state for about a second, never the Offline screen,
      and locks read "unavailable" → real state. A launcher shortcut ("Lock up") from that
      backgrounded state still works, and a push still arrives while backgrounded.

## Doorbell (both)

- [ ] **Ding banner fires.** Press the real doorbell → the in-app banner drops in within a couple of
      seconds; **View** opens that camera's live player.
- [ ] **Push fires** (once Gate 3 ships): with the app closed, the ntfy push arrives and tapping it
      deep-links to the camera.

## Clip export (Frigate only — run on BOTH platforms)

Automated tests cover the selection maths, the signing, the URL shape and the download handoff
against `mock-ha`. What they cannot cover is whether the bytes Frigate actually produces are a
playable clip of the right moment.

- [ ] **Gate.** The **Clip** chip appears on the Reolinks once scrubbed back, and is **absent**
      while live, on every Ring camera, and on the doorbell.
- [ ] **Mark and save.** Scrub to a known event → **Start here** → scrub forward → **End here** →
      Download. The file lands (browser downloads / `Movies/Hawksnest`), plays in VLC and in the
      system player, is the right camera, the right ~30 s, **with audio**.
- [ ] **Trim accuracy.** The clip's real start can be up to one GOP *after* the requested second
      (`-c copy` cannot cut mid-GOP). Confirm that is seconds, not tens of seconds — if it is the
      latter, the cameras' keyframe interval is the thing to look at, not this app.
- [ ] **Record the wall-clock time and file size of a 10-minute export here: ______**. This is the
      only real evidence for whether `MAX_CLIP_MS` is set right; re-tune the constant from it.
- [ ] **`bedroom` retains 1 day, not 3** (a per-camera override the retention sensor does not
      expose). Drag the selection to two days back: Download must be blocked by the footage-coverage
      check with "No recording exists for this range" — **not** by a server 400.
- [ ] **Gap.** A range straddling a Frigate restart warns ("Part of this range wasn't recorded")
      and still exports the footage that exists.
- [ ] **Android share.** After a save, **Share** opens the sheet and the clip sends intact.
- [ ] **Android backgrounding.** Start an export and leave the camera screen (not the app): it
      still completes. Leaving the *app* during a long export is a known limitation.
- [ ] **Signature is real.** The URL in the browser's download list carries `authSig`; re-opening
      it an hour later 401s (expiry is not decorative).
- [ ] **App stays responsive** while an export runs — entities keep updating and live view still
      paints. This is the check on an export occupying the HA connection.

## Battery cameras behind the Home Hub (the Argus 4 Pros) — run on BOTH platforms

These are Frigate cameras that **sleep**: parked OFF in Frigate until the hub's PIR fires, woken
by any RTSP request (up to ~20 s), force-slept by the hub after 5 minutes. Everything below is a
consequence of that, and none of it is covered by the mock (`frigate-battery-camera` scenario
renders the shapes, not the timing).

- [ ] **Asleep tile.** With nobody in front of the camera for a few minutes, its wall tile shows
      the last live frame (or the camera placeholder) captioned **Asleep**, a grey dot, and the
      badge reads **Motion Xm ago** — never "2m ago" with a green dot over a stale frame, and never
      Frigate's grey error image.
- [ ] **Tile wakes with the PIR.** Walk in front of it: within seconds the badge flips to "Motion
      just now", and once Frigate enables the camera (HA state `streaming`) the tile resumes
      refreshing on the shared beat.
- [ ] **Live wake, honestly labelled.** Open the camera cold: the overlay says **Waking camera…**
      (not "Connecting…") and the picture arrives within ~20–30 s. It must NOT step down to a
      snapshot at 8 s.
- [ ] **A slow wake costs no other camera.** Immediately after a battery camera wakes slowly (or
      fails to), open a wired camera: it still takes the go2rtc tier (first frame in 1–2 s). If it
      lands on HA WebRTC/HLS instead, the session breaker tripped — that is the regression.
- [ ] **Five-minute cap.** Keep watching live past 5 minutes: the hub drops the session; the
      player either re-wakes cleanly or steps down with a visible state — never a frozen frame
      behind a green badge.
- [ ] **Gap placeholder.** Scrub to a moment with no event: **"No saved recording for this
      moment"** — not "Couldn't load this recording" + Retry. On Android too (it used to stall
      silently).
- [ ] **Island playback seeks to the right second.** Scrub inside an event: the video shows the
      moment under the playhead, not the start of the woken window and not the wrong minute.
      Drag across the island: same element, no reload; step off it: the placeholder.
- [ ] **Battery.** Note `sensor.<slug>_battery` before and 24 h after; it must not fall faster than
      it did before the camera was integrated (the whole design exists for this line).

## If something fails

- Camera frames 400 / never paint over the TLS path → suspect `X-Forwarded-For` leaking to HA;
  confirm nginx clears XFF/XFP for every HA-proxied `location` (deploy/README.md, CLAUDE.md invariant).
- Live spins forever but recorded works → go2rtc/WebRTC negotiation, not the app shell.
- Doorbell banner silent → check the `_ding` binary_sensor is arriving in the entity stream
  (`activeDoorbellPress` in `src/lib/doorbell.ts` keys off it).
