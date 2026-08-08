# ARCHITECTURE.md — Hawksnest (software-level)

How this repo is organized and why. Suite-level context: `C:\Code\ARCHITECTURE.md`. Working
instructions + production-breaking invariants: [CLAUDE.md](CLAUDE.md). Feature/dev/testing
overview: [README.md](README.md). Backlog: [ROADMAP.md](ROADMAP.md).

Hawksnest is the **odd one out** in the suite: a presentation layer over **Home Assistant**
(HA stays the brain), no backend of its own, no public ingress (LAN/Tailscale only), deliberately
excluded from the Dragonfly config broker and SSO (it authenticates directly to HA). It fronts
the thing that unlocks the house's doors — security posture outranks features here.

## System shape

```
Web SPA/PWA (React/TS, nginx pod) ──same-origin /api──▶ nginx ──proxy──▶ Home Assistant (k3s, WSL2)
Android app (Kotlin/Compose) ──Tailscale, long-lived token──────────────▶ HA WebSocket + REST
                                                    HA ⇄ ring-mqtt (+go2rtc) ⇄ Ring doorbell/cams
                                                    HA ⇄ Z-Wave JS (locks, etc.)
```

One repo, three deliverables:

## 1. Web SPA/PWA (`src/`)

React + TypeScript (Vite), Tailwind mapped onto PULSE CSS tokens (`src/theme/tokens.css` — a
**CSS port** of the design language, *not* the `design.pulse:pulse-ui` Gradle library; that's
Compose-only). Dark is the default; a light variant (`:root.light`) is opt-in via Settings →
Appearance. `src/store/theme.ts` owns the Dark/Light/System preference (persisted), toggles the
class on `<html>`, and an inline script in `index.html` applies it before first paint (no flash).
Channel hues intentionally shift between themes so a vivid accent still clears contrast on white.

| Area | Responsibility |
|---|---|
| `src/lib/` | The domain kernel. `cameraModel.ts` collapses ring-mqtt's split entities (`_snapshot` + selectors/ding/motion, plus `_live`/`_event` cameras on ring-mqtt 4.x) into one logical camera; `cards.ts` maps HA domains → card components (**must never throw** — unknown domains render `GenericCard`); `resolve.ts` centralizes label/icon resolution (per-entity overrides go in `src/config/overrides.ts`, never in components) |
| `src/store/` | Client state: HA WebSocket connection, auth, entity registry, reconnect logic. The entity sink dedupes the Ring-vs-ring-mqtt double exposure centrally (`src/lib/dedupe.ts`, platform map from the registry): when both integrations expose the same light, the ring-platform twin is dropped so every consumer sees one entity per physical device. The store also keeps the offline bookkeeping (`lastConnectedAt`/`staleSince`, stamped on leaving `connected`) and masks lock/alarm states to `unavailable` at the drop moment (`lib/offline.ts` — see the offline invariant below); `retryConnection()` restarts the source to skip the websocket lib's internal backoff |
| `src/screens/` + `src/cards/` + `src/components/` | Presentation; no raw hex — PULSE tokens only. Loading states use the shared `Skeleton` (one hairline-strong shimmer sweep — camera first-frame decode, history fetch); the dashboard arm discs activate via a channel fill-sweep, still non-optimistic (the sweep follows HA's echo, pinned in tests) |
| `src/config/` | Entity/room overrides |
| `public/` + service worker (vite config) | PWA shell. **The SW never caches `/api` and never touches the HA token** — offline = shell + Offline/Demo state, never stale entity data. Updates are **prompt**, not silent (`registerType:"prompt"`): `UpdateToast` (useRegisterSW) surfaces a "reload" prompt when a new shell is waiting, so a wall tablet that never navigates isn't stranded on a stale build |

Camera streaming: the live transport ladder (`LivePlayer`) is **go2rtc-direct → HA WebRTC → HLS →
MJPEG → snapshot-poll**. The top tier (`Go2rtcPlayer`) negotiates WebRTC straight with the dedicated
go2rtc over its WS API (`/go2rtc/api/ws?src=<base>`, proxied same-origin by
nginx) — no ring-mqtt/ffmpeg hop, ~1–2 s first frame, and the same signaling the Talk backchannel
uses. It's offered for **every** camera, not just Ring ones: go2rtc fronts the Reolink main stream
too, so which cameras it can serve is go2rtc's own stream list to answer, not something inferred
from the camera's kind. The gate is `go2rtcMaybeAvailable` in `lib/go2rtc.ts`: a cached
`/go2rtc/api/streams` list + a **session circuit-breaker** that skips the tier once media is
known-unreachable — e.g. before the §7c `:8555` host forwarder is up — so there's no repeated stall.
`LivePlayer` **waits for that list** (`primeGo2rtcStreams` resolves, `go2rtcStreamsKnown`) before
taking the tier; the list's absence used to read as an optimistic "yes", which was harmless while
only Ring cameras were offered it and would now stall every camera go2rtc doesn't serve.
The next tier, HA WebRTC, negotiates over `/api/websocket` (media UDP direct to HA's go2rtc) and
is gated on the camera's STREAM `supported_features` bit with **absent treated as "try"**
(`canStreamWebRtc` in `lib/cameraUrl.ts` — modern HA dropped the old `frontend_stream_type`
attribute, and a battery cam's entity churns attribute-less mid-negotiation), holds a 20 s
watchdog + "Connecting…" overlay for battery-camera wake, and the HLS tier resolves its
`camera/stream` URL **only when that tier is active** (an eager resolve wakes the camera twice)
with a 15 s bound in `haSource`. Tile age badges use `snapshotFreshnessMs` (`timestamp` attr →
`last_updated` → `last_changed`) — `last_changed` alone reads hours-stale on cameras.

**Snapshot refresh is per-backend, driven by two counters, not one**
(`components/snapshotBucketContext.ts` → `useSnapshotBucket(isFrigate)`, Kotlin twin
`core/logic/CameraUrl.kt#snapshotBucket`). `shared` ticks on one app-wide ~10 s beat while the app
is foregrounded, so tiles refresh together rather than on N timers and a backgrounded app stops
polling. `onOpen` is that beat plus a tick on every app-open/foreground, and **only Frigate cameras
ride it**: Ring's proxy is metered and `hawksnest_ring_snapshot_policy` pins battery cameras to a
300 s snapshot interval, so refetching Ring on open spends a metered request to receive the same
image, while a Frigate snapshot is local and current the instant it is asked for. Both counters are
**seeded from the clock rather than 0** — starting at 0 made every session's first request
byte-identical to the last one's, so the HTTP/Coil cache could serve an arbitrarily old opening
frame. The seed only has to differ across sessions; increments stay monotonic within one.

**Which backend holds a camera's recordings is a three-way derivation, not a boolean**
(`lib/recordedBackend.ts` → `"ring" | "frigate" | "none"`, ported to `core/logic/RecordedBackend.kt`).
It used to be `isRing = camera.eventSelectId !== null`, one flag standing in for three unrelated
questions — where recorded events come from, whether playback is a per-clip resolution or a seekable
VOD, and whether the go2rtc live tier applies — which only held while the sole NVR was Ring. Ring
wins when a camera looks like both, because the Ring path owns the retry/signature-expiry mechanics.
Frigate membership comes from `lib/frigate.ts` (`isFrigateCamera`, Kotlin twin
`core/logic/Frigate.kt`): the frigate-hass-integration stamps `client_id` + `camera_name` onto the
`camera.*` entity it creates, and membership is read off those attributes — synchronous, no fetch.
(The `/api/frigate/config` read this used to do targeted a route the integration never proxies, so
every camera silently resolved "no Frigate".) It **fails closed**, the opposite of `go2rtc.ts`,
because a wrong "yes" silently turns the demo loop into a broken NVR path, whereas a wrong "no" is
just today's behaviour. Derived per render rather than stored on `LogicalCamera` so
`cameraModel.ts` stays synchronous. Android's `CameraPlayer.kt` derives the same three-way backend
(2026-07-30, closing the known lockstep gap where it still read the raw `eventSelectId` boolean).

**Camera object alerts + AI descriptions (2026-07-30).** Frigate detections now reach the phone:
the HA automation `hawksnest_push_camera_object` (sibling repo) subscribes to Frigate's
`frigate/events` MQTT topic and publishes "There is a person at your Kitchen" to ntfy, **only
while the alarm is armed** — indoor cameras plus an occupied house produce ~10 person events in
minutes, so ungated alerts were never viable. Two HA `input_boolean` helpers
(`hawksnest_alert_person` / `_pets`) gate it *server-side*, so a muted class never generates a
push at all; both platforms' Settings write them (`CameraAlertToggles.tsx`,
`SettingsViewModel.personAlerts`). That's household policy, distinct from Android's device-local
"Push alerts" subscription switch.

Client-side, `PushRoute.kindOf` routes the `walking`/`dog`/`cat` tags to new `PushKind.Person` /
`Pet`, each with **its own notification channel** (`camera_person` HIGH + `CATEGORY_ALARM`,
`camera_pet` DEFAULT) so pets can be muted without touching person alerts. Channel ids are new
rather than re-tuned: Android ignores importance changes to an already-created channel. Camera
alerts key their notification id on the **camera**, not the message — Frigate tracks each object
separately and one person crossing a room produced three concurrent tracked objects on the live
kitchen camera, which would otherwise post three notifications.

`CameraEvent.description` carries Frigate's GenAI text (`lib/cameraEvents.ts` ↔
`core/logic/CameraEvent.kt`). It needed no new transport — the integration proxies `/api/events`
verbatim, so it was already arriving and simply wasn't mapped. `EventDescription` renders it under
the timeline, driven by the event under the playhead (tapping a chip already seeks, so the lookup
costs no new interaction). It distinguishes three states, because "no text" means different
things: a description; a **pet** event (Frigate runs GenAI for `person` only, so these never get
one); and a **fresh person** event whose description hasn't been generated yet (it lands after the
event ends). The notification deliberately carries only the short line — the description is
multi-paragraph and doesn't exist when the alert fires.

**Camera movement (PTZ/zoom/focus, both platforms, 2026-07-30):** Hawksnest drives the lens
through Home Assistant's **official Reolink integration** — `button.<slug>_ptz_{up,down,left,
right,stop}`, `number.<slug>_{zoom,focus}`, `switch.<slug>_auto_focus`, optional
`select.<slug>_ptz_preset` — never by talking to the camera directly. The integration owns the
camera's API session and serialises commands; the app only presses buttons and sets numbers
(`servers enforce, clients present`), and needs no new network path since it all rides the
existing WS `call_service`.

**The entity ids are discovered, not derived** (`lib/cameraPtz.ts` ↔ `core/logic/CameraPtz.kt`).
`button.${cameraBase}_ptz_up` is wrong here and fails *silently*: the stairway's Frigate entity
is `camera.first_floor_stairway` while its Reolink device is `stairway`. So candidate slugs are
read out of the entity ids that exist and matched to the camera — exact wins, else one slug must
be the other's trailing whole segments; ambiguous matches and half-present sets (a pad that can
move but not stop) resolve to **null**, and an `aliases` map pins anything a heuristic shouldn't
decide. Capability is per-entity, so an E1 Zoom gets pad+zoom+focus and an E1 Pro gets the pad
alone without the app knowing the model.

The pad presses on touch-down and sends `ptz_stop` on release — correct whether a press moves
continuously or one step (unverified; it physically re-aims a recording camera, so it is a
smoke-test item). **The camera must never be left moving** is the invariant the tests pin:
stop fires on release, cancel, unmount, and app-background/tab-hide, and a second direction is
ignored while one is in flight. No speed control — the hardware reports `supportPtzSpeed:
permit 0`.

**Player chrome (both platforms, 2026-07-30):** a mute toggle (`MuteButton` — every tier mounts
muted for autoplay policy; the toggle flips the element/AudioTrack live, and on Android gates the
WebRTC `AudioTrack` that org.webrtc would otherwise auto-play), snapshot-to-file
(`SnapshotButton` — blob download on web, MediaStore `Pictures/Hawksnest` on Android Q+), and a
Low/High live-quality toggle (`QualityToggle`) that swaps the go2rtc tier onto the camera's
`<name>_sub` stream. The toggle renders only when go2rtc's stream list carries the `_sub` name, so
Ring/demo cameras never see it; on Android, Low also bypasses the RTSP-direct tier (which plays the
fixed-bitrate main stream — the thing a weak link is trying to escape).
`"none"` (demo / no NVR) is the only backend whose media loops, and the only one that ignores
playback errors — the source hands it the same bundled clip for every seek.

Recorded playback = the last ~5 Ring events via the event-selector entity on `"ring"`; on
`"frigate"`, one continuous VOD spanning the window (`recordingUrlAt`). That VOD URL is built from
the window alone, so it exists whether or not Frigate kept anything — a scrub into a gap mounts a
playlist that 404s, and the player tracks the failed src (`vodFailed`) to swap in the placeholder
with a Retry rather than sit dead. Replacing that with real gap knowledge means normalizing
Frigate's `/recordings` into the `ringFootage.ts` segment shape; not done yet.

**The Frigate VOD is PAGED, not one continuous manifest.** Frigate serves `/vod/` through
nginx-vod-module, whose durations array has a hard ~1024-element ceiling; past it the request 503s
outright (`media_set_parse_durations: invalid number of elements`). Measured 2026-07-29: 220 min /
940 segments returns 200, 230 min does not — roughly a **3-hour ceiling** at these segment lengths,
and not configurable without rebuilding the module. The earlier "one continuous VOD spanning the
window" was therefore never workable: the window was a hardcoded 24h, already 8× over. So the
timeline and the media are decoupled — the **timeline** spans the camera's real retention
(`record.continuous.days`, read from `/api/frigate/config`; Ring stays at 24h because its recorded
path is a handful of cloud events), while the **media** is a bounded 2h page that follows the
playhead (`lib/vodWindow.ts` ⇄ `core/logic/VodWindow.kt`, ported 1:1 and tested on both). Pages are
grid-aligned so scrubbing *within* one yields the identical URL — no re-prepare, cache stays warm,
and the page's signature stays valid. Zoom-out is no longer capped at 24h (`maxSpanMs` is now the
window itself), or a 3-day retention could be panned but never seen whole.

**`/api/frigate/config` is NOT a route.** frigate-hass-integration proxies snapshot, recording,
thumbnail, clips, notifications, vod, jsmpeg, mse, webrtc and go2rtc — not config. It 404s
(verified 2026-07-30), so anything built on it silently reports "no Frigate". Camera membership
therefore comes from the `camera.*` entity the integration creates, which stamps `client_id` and
`camera_name` (`lib/frigate.ts`) — synchronous, no fetch, cannot go stale. Retention is **not
exposed by HA at all**, so `FRIGATE_RETENTION_DAYS` / `FRIGATE_RETENTION_DAYS` (web/Android) is a
constant that **must be kept in step with `record.continuous.days` in the Frigate seed by hand**.
Too low and kept footage is unreachable; too high and the timeline offers days that 404, which
degrades to the "no recording kept" placeholder — so it fails safe in the direction of guessing high.

**`GET /api/frigate/events` is not a route either — the integration's query API is
websocket-only.** Registered REST views proxy *media* (the list above); queries live in
`ws_api.py` as websocket commands: `frigate/events/get`, `frigate/recordings/get`,
`frigate/recordings/summary`, and friends. The original event fetch on both platforms hit the
REST path, 404'd on every call, and the defensive catch turned that into an empty list — so every
Frigate camera's timeline silently rendered without a single event chip, and the tests passed
because they stubbed the nonexistent route (the identical failure mode to `/api/frigate/config`
above; verified against the registered view list and the working command, 2026-07-30). Events now
ride `frigate/events/get` over the existing HA socket (`haSource.ts fetchFrigateEvents` ⇄
`HaSource.fetchCameraEvents`). Two contract details worth knowing: `instance_id` is required —
it's the same `client_id` the integration stamps on the camera entity, so it is read from state,
not hardcoded — and the result arrives as a JSON **string** (the integration skips decoding),
unwrapped by the mirrored `parseFrigateWsEvents` (`lib/cameraEvents.ts` ⇄ `core/logic/CameraEvent.kt`).
`frigate/recordings/get` powers the **Frigate continuous footage lane** (plan item 8b, built
2026-07-30): the same shaded "recordings exist here" track Ring cameras have, now drawn for
Frigate cameras from real recording segments. The payload is one entry per ~10 s segment
(measured: ~6.5k entries / 1 MB / tens of ms for a 3-day window), so the mirrored
`parseFrigateWsRecordings` (`lib/ringFootage.ts` ⇄ `core/logic/RingFootage.kt`) coalesces to
drawable `FootageSpan`s at the parse boundary — 15 s tolerance, chosen to bridge a single
dropped ~10 s cache segment while leaving real gaps honest. Spans are always `playable: true`
(no per-segment URLs to expire, no Ring-style E2E encryption). It reaches the player through the
source seam (`Source.fetchCameraFootage`, [] when unsupported — demo/mock render laneless, as
before) and is **visual only**: playback stays on the paged VOD; the lane shows *where* scrubbing
will land on footage. One trap the Android wiring hit: the fetch must be keyed on the window, not
just the camera — the window opens at the 24h fallback and widens to real retention when
`frigateRetentionDays` resolves, and an unkeyed fetch would leave days 2–3 laneless (the event
fetch had the same latent bug; both are keyed now).

**A Compose page-turn trap that froze scrubbing (fixed 2026-07-30).** `produceState` does NOT
reset its value when its keys change — only the producer restarts — so when the playhead crossed
a VOD page boundary, the signed URL held the *old page's* URL until the new signature arrived,
then swapped directly to the new one with no null in between. `VideoPlayer` therefore never
unmounted, and inside it `remember(authSig, …)` built a fresh ExoPlayer mid-composition while
`DisposableEffect(Unit)` never released the old one and the `AndroidView` (whose factory runs
once, with no `update` block) stayed bound to it: the picture froze on the old page while the new
player streamed invisibly, and every crossing leaked another live player. Ring never tripped this
because its URLs carry no authSig — the remember key never changed identity. Three guards now
hold: the signed-URL producer resets to null first (placeholder covers the sign round trip — same
fix on web, whose `useState` kept the stale URL too, briefly playing the old page at a
wrong-page-relative seek), `DisposableEffect` is keyed on the player, and `AndroidView` has an
`update` that re-points the view. If a player identity can change without its host unmounting,
all three must key on it.

**Frigate VOD URLs must be signed, and the signature has to reach the segments.**
frigate-hass-integration validates an `authSig` query parameter on every VOD *segment* request
(`VodSegmentProxyView`) — unconditionally, and a Bearer token does not satisfy it. Playlists are
exempt, so an unsigned URL produces the worst possible symptom: `master.m3u8` and `index-*.m3u8`
both load, every `.m4s` 401s, and the player shows a black frame with no error. Android obtains
the signature via `auth/sign_path` over the existing websocket
(`Source.signedRecordingUrlAt` → `HaSource`), which also removes any need for an `Authorization`
header on media requests — a signed URL authenticates on its own. Because players resolve segment
URIs *relative* to the manifest and drop the query string, `VideoPlayer.kt` wraps the
`DataSource.Factory` to re-attach `authSig` to every request. One signature covers the whole
window (the integration checks it as a path prefix), but it is scoped to that `start/end`, so a
new scrub window re-signs. **Web closes the same gap in `HlsPlayer`** — hls.js `xhrSetup`
re-attaches `authSig` (plus a Bearer for the nested `index-*.m3u8`); the caveat that remains is
native HLS (Safari/iOS), which has no such hook and still 401s on segments.
The **timeline shows only playable recordings**
(Ring-style: every block is watchable) — the selector's ~5 on ring, clip-bearing events on
Frigate/demo; no history-derived markers. Scrubbing is **live**: `Timeline24h` streams the time
under the center playhead during a drag (`onScrub`, rAF-throttled) and the playhead is a true ms —
inside a kept clip the video seeks in real time (forward and reverse) at the in-clip offset
(`clipSeek.ts`, mirrored in `core/logic/ClipSeek.kt`; a ring clip's real span is learned from the
loaded media's duration since `endMs` arrives null), and release keeps playing from that moment.
**Recorded ring events come from the `ring-timeline` service** (sibling repo `hawksnest-automation`,
proxied same-origin at `/ring-timeline/`), not from HA: it serves Ring's own `video_search` timeline
— real event times, real spans, thumbnails, person flags, and pre-signed mp4 URLs the player loads
directly (`lib/ringTimeline.ts`). This exists because HA cannot supply it: ring-mqtt's event
selector carries **no event times** (blocks used to be plotted on fabricated 6-minute spacing) and
produces nothing playable at all on the wired cameras. Cameras are matched to Ring devices by
**display name**, not entity id — the ids froze at first discovery and have drifted (`camera.front_*`
is Ring's "Front Driveway"). Ring signs those URLs for ~15 minutes, so the player refetches a minute
ahead of the earliest expiry and once more on a playback error before calling a clip failed. When
the service is unreachable the player falls back to the ring-mqtt selector path below, which is:

Ring clip **URLs come off the selector itself**: ring-mqtt 5.x has no `camera.<base>_event` entity —
selecting an option makes it fetch Ring's signed cloud recording (an expiring S3 mp4) and republish
the selector with a `recordingUrl` attribute, so `store/ringClip.ts` (mirrored in
`CameraPlayerViewModel.resolveRingClip`) selects, then waits for that attribute to arrive **for the
selected option** (20 s bound; `<Recording Not Found>` fails immediately, `<Transcoding in Progress>`
keeps waiting). A `camera.<base>_event` entity, where one exists (ring-mqtt 4.x), still takes the
`camera/stream` path. Resolution runs through an explicit per-clip state machine (`RingClipState`:
resolving → ready/**failed**): a recording Ring can't produce (timeout, rotated-out event, no Protect
subscription, playback error) surfaces as "Couldn't load this recording" **with a Retry** —
never a stuck loader — while a time with no kept recording shows the honest "no saved recording"
note over the snapshot. Rendered Ring-style — solid `effort`-blue blocks, a triangle playhead, a
dim "Live" region right of now, a "TODAY" header (`Timeline24h`) — over the tested
`timelineViewport` math.

**The timeline opens at a 1h span** (`DEFAULT_SPAN_MS`, in `timelineViewport` rather than in the
two components, because a constant duplicated across platforms is one that drifts). It was 8h,
which on a phone-width track put a 30 s event under one pixel: adjacent events merged into a solid
bar and the strip only ever said "something was recorded today". The zoom range is unchanged
(10 min – full retention) and pinch reaches all of it. Anything that needs to click an event chip —
E2E specs especially — must now assume only the last ~30 minutes are on screen.

**Pinch-to-zoom over the picture** is `lib/videoZoom.ts` ↔ `core/logic/VideoZoom.kt` (clamping and
focal-point math, tested on both) behind the `ZoomableFrame` component/composable. It wraps the
*whole* player ladder rather than any one tier, so all seven transports (RTSP, go2rtc WebRTC, HA
WebRTC, HLS, MJPEG, snapshot, recorded VOD) magnify identically instead of seven times differently.
It is a **view** transform — bigger pixels, not a sharper image, and it does not move the camera;
real optical zoom is PTZ (above). `shouldCaptureDrag` is shared because it encodes the one rule
both platforms must agree on: an unzoomed frame must let a drag through so the page can still
scroll. Fullscreen lives in the same component — on Android it rotates to landscape and hides the
system bars, which is why `MainActivity` now declares `configChanges` for orientation (without it
the rotation recreates the activity and costs a 2–4 s WebRTC renegotiation).

**The 24/7 continuous track is a second, separate source** (`/footage`, `lib/ringFootage.ts`,
mirrored in `core/logic/RingFootage.kt`). `video_search` returns only discrete *events*, so before
this a quiet 3–5 AM window read as "no recording" on the seven cameras that record all night. Ring
stitches the window server-side, so the normal answer is ONE segment spanning the whole request —
the same "one VOD, seek within it" shape the Frigate path uses. It is drawn as a low strip along
the bottom of the timeline, under the event blocks, and marked `· 24/7` in the caption; the battery
cameras and the doorbell have no such track and get no lane. **Which source plays is decided by one
pure function** (`chooseRecordedSource`, ported 1:1 and tested on both platforms, so the platforms
cannot drift on the behaviour users actually see): continuous footage wins wherever it covers the
moment — one media source means scrubbing seeks instead of re-initialising the player per clip —
and the event clip is the fallback in its gaps and on the event-only cameras. Ring can mark a span
end-to-end encrypted; those stay in the model and draw neutral, and a moment covered *only* by one
says "This footage is end-to-end encrypted" (not a failure, and not "nothing recorded"). Footage
URLs are signed on the same ~15-minute clock as the event URLs, so both are fetched together and
whichever expires first drives one shared refresh; the once-per-source error refetch is keyed by
segment for the continuous track, since the user can sit on one segment longer than a signature
lives.

## 2. Android app (`android/`, package `com.hawksnest`)

Kotlin/Compose, talks to HA directly over Tailscale with a long-lived token. Full guide:
`android/README.md`.

- `core/ha/` — HA WebSocket/REST client (the Kotlin analogue of the web store). **All user-facing
  control calls go through `ControlGate`** (via `ConnectionManager.control`): it is the crash-safety
  layer (a failed call becomes a message on the app-level snackbar, never an uncaught coroutine
  exception) and the honest-pending tracker (entity id held in `pendingControls` until HA echoes,
  the call fails, or a 30 s timeout reports "didn't respond"). Raw `callService` is reserved for
  screens that surface their own errors (lock keypad codes, Z-Wave maintenance). `HaState` also
  carries the offline bookkeeping (`lastConnectedMs`/`staleSinceMs`/`nextRetryAtMs`/
  `hostReachable`, all in-memory `StateFlow`s) and applies the lock/alarm stale-state mask on
  leaving CONNECTED; `HaSource`'s reconnect backoff is skippable via `Source.retryNow()`
  (`RetrySignal`) and fires one bounded `core/net/ReachabilityProbe` per cycle — see the offline
  invariant below and `core/logic/Offline.kt` (the pure model: grace window, countdown,
  "as of" formatting, mask).
- `core/net/RingTimelineClient.kt` + `core/logic/RingTimeline.kt` + `core/logic/RingFootage.kt` —
  the `ring-timeline` service (Ring's own recorded timeline **and** its 24/7 continuous track),
  read through the SAME origin the app already talks to, so it needs no new host, credential, or
  external surface. Ports the web `lib/ringTimeline.ts` / `lib/ringFootage.ts` 1:1, including
  matching cameras to Ring devices by display name and the shared `chooseRecordedSource` policy.
  Both halves come back from one call (`CameraPlayerViewModel.ringRecorded`) because they need the
  same device lookup and expire on the same clock. Returns null on any failure — the player falls
  back to the ring-mqtt selector rather than breaking the camera screen; an empty footage list is
  a real answer (event-only camera), not a failure.
- `core/logic/`, `core/automations/` — entity → domain-model mapping, automation surfaces.
  Includes the ring/ring-mqtt dedupe (`Dedupe.kt`, applied centrally at `HaSource`'s entity sink,
  mirroring the web) and the Devices sectioning model (`DeviceSections.kt`: per-room three-tier
  rhythm — FEATURED lock/climate/alarm cards, CONTROL rows with inline switches, READONLY rows).
- `ui/<feature>/` — home/rooms/area/devices/cameras/entity/history/automations/settings.
- **The history feed is capped and lazy** (`lib/logbook.ts` ↔ `core/logic/Logbook.kt`,
  `capLogbook`/`LOGBOOK_MAX_EVENTS`). This instance's recorder logs **~98,000 state rows a day**
  (measured against the live MariaDB), and `logbook/get_events` was asked for up to 30 days of them
  unbounded. Android then composed a row per event eagerly in a `Column(verticalScroll)` and the
  app died — and nothing caught it, because an `OutOfMemoryError` is an `Error`, not the
  `Exception` the fetch guards. Two independent fixes, both needed: the feed is capped to the 500
  newest **after** the noise filter (so the kept events are useful ones), and the screen is a
  `LazyColumn` so cost stops scaling with the window. The cap is honest — the screen says when it
  truncated rather than letting the day appear to end early.
- **Camera live ladder** (`ui/cameras/CameraPlayer.kt`): recorded VOD (when scrubbed) →
  **RTSP-direct** → **go2rtc-direct** → HA WebRTC → HLS → MJPEG → snapshot. The go2rtc-direct
  tier (`Go2rtcPlayer.kt`) negotiates recvonly WebRTC straight against the dedicated go2rtc over
  its WS API (`/go2rtc/api/ws?src=<base>`, same signaling `TalkButton` speaks — both share
  `Go2rtc.kt`'s `go2rtcWsUrl`), skipping the ring-mqtt/ffmpeg hop for ~1–2 s first frame. Media
  is WebRTC to go2rtc's `:8555`; when that's unreachable (§7c host forwarder down / off-tailnet)
  the 8 s watchdog fails over to HA WebRTC and `Go2rtcHealth` (process-wide circuit-breaker)
  makes every later camera skip the tier. Shares `WebRtcCore` (process EGL/factory — never
  disposed per-session) and `LiveFrameStore` tile capture with `WebRtcPlayer`.
  The tier is offered for **every** camera, gated on go2rtc's own stream list
  (`core/net/Go2rtcStreams`, the 1:1 port of web's `lib/go2rtc.ts` cache — same 60 s TTL, same
  fail-to-EMPTY-set rule, and it hosts `Go2rtcHealth` since `core` cannot depend on `ui`).
  It previously gated on `isRing`, which was only ever a proxy for "go2rtc serves this": true
  while go2rtc served Ring exclusively, wrong once the Reolink main streams were added, and the
  reason those cameras fell to **segmented HLS and looked jumpy**. The stream list is what makes
  dropping `isRing` safe — without it every camera go2rtc doesn't serve would pay the full 8 s
  watchdog on open. Its decision is tri-state: while the (cached, sub-second) list is in flight
  the ladder holds both WebRTC arms and renders the snapshot, and **must not** resolve HLS —
  `camera/stream` wakes a battery camera's pipeline, which is what the lazy-HLS rule exists to
  prevent. Both WebRTC tiers are continuous RTP; HLS below them is segmented, which is what
  "jumpy live video" actually is.
- **Camera sound is MEDIA, not a call** (`WebRtcCore`, `ui/cameras/CameraAudio.kt`). libwebrtc's
  default audio device module builds its `AudioTrack` with `USAGE_VOICE_COMMUNICATION` +
  `CONTENT_TYPE_SPEECH`, so opening a camera interrupted other audio, bound playback to the
  **in-call** volume slider and could route to the earpiece. Muting could never have fixed it: the
  mute gate disables the *received track*, while the ADM starts its `AudioTrack` as soon as the
  audio transceiver negotiates. The factory therefore supplies an ADM with `USAGE_MEDIA` +
  `CONTENT_TYPE_MOVIE`. Playout only — `TalkButton` captures the mic through the same factory and
  is unaffected. On top of that the player pins `volumeControlStream` to `STREAM_MUSIC` while a
  camera is open (the default resolved to the *ringer* on a silent camera) and takes
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` **only while unmuted**, so opening a camera never interrupts
  anything and unmuting ducks rather than stops it. The ExoPlayer tiers already defaulted to media
  attributes and requested no focus, so this covers every transport uniformly.
  **Both effects resolve the Activity by walking the `ContextWrapper` chain, not
  `LocalContext.current as? Activity`** — Compose usually hands out a `ContextThemeWrapper`, so the
  cast returns null and the effect no-ops into the same branch that legitimately means "not in an
  activity". That is how fullscreen shipped without ever rotating.
- **Talk is a latch, and it is live-only** (`ui/cameras/TalkButton.kt`). A tap opens the mic and a
  second tap closes it; hold-to-talk lost the mic whenever a finger moved. The live-only mount in
  `CameraPlayer` is **load-bearing, not cosmetic**: unmounting the composable is what closes the
  session, so it is the guarantee that a latched mic can never be open over recorded footage.
  Anything that lifts this control somewhere surviving the live/recorded switch must replace that
  guarantee with an explicit one.
- **Who gets a Talk button is asked of go2rtc, not of the camera's kind**
  (`core/logic/canReachSpeaker`, web: `canReachSpeaker` in `camera/CameraPlayer.tsx`). go2rtc is
  what carries the audio backchannel, so "does go2rtc serve this stream" is the whole question —
  the same predicate Reply uses, so the two speaker features can never disagree about a camera.
  It **fails closed**: no button while the stream list is in flight, none at all for a stream
  go2rtc does not list. On web that means `go2rtcStreamsKnown() && go2rtcMaybeAvailable(...)`,
  because `go2rtcMaybeAvailable` alone is deliberately optimistic before the list lands — right
  for a transport that can step down, wrong for a button.
  Until 2026-08-05 this gated on `isRing`, which is a fact about where a camera's **recordings**
  live. That is the third appearance of the same category error (the live tier and the recorded
  backend were the first two), and it cost the seven Reolinks a feature they support: E1 Zoom and
  E1 Pro carry an ONVIF backchannel and advertise `PCMU/8000` sendonly, which is how Reply has
  been reaching their speakers all along. **The stream name is the HA entity base**, so a
  mismatch between the two — as four Ring cameras had until the same date — reads to this gate as
  "go2rtc does not serve it" and correctly shows no button.
- **RTSP-direct** (`ui/cameras/RtspPlayer.kt`, `core/logic/ReolinkRtsp.kt`) — the top live tier and
  the **one place the two platforms' ladders legitimately differ**: browsers cannot play RTSP at
  any level, so web's ceiling is and stays WebRTC. This is not web lagging Android; it is a
  capability gap, and `ReolinkRtsp.kt` deliberately has no web twin.
  It plays the camera's own `rtsp://user:pass@ip:554/h264Preview_01_main` — what the vendor app
  does, and the shortest path there is (no relay, no re-packaging). A dedicated composable rather
  than a `VideoPlayer` mode: VideoPlayer's construction is built around HLS/VOD concerns (authSig
  + Bearer data-source wrapping, live-edge offsets, seek-within-media, duration reporting), none of
  which apply here.
  **Off by default.** It needs a camera account and a per-camera IP, both entered in Settings
  (`ui/settings/RtspPanel.kt`) and stored in `CredentialStore` with the password under the same
  Keystore wrap as the HA token. Unconfigured, the ladder behaves exactly as before. Nothing in
  the repo carries a real IP or account — it is public.
  **Fails fast, three ways**, because the tier is optional and a dead frame is worse than a
  step-down: a 4 s RTSP connect timeout, a 5 s no-first-frame deadline (an unreachable camera can
  hang setup without ever erroring), and a 7 s post-play stall timeout. That last one matters
  because the main stream is FIXED-bitrate — a weak link degrades to a stall, not to lower quality,
  whereas go2rtc's WebRTC below it adapts. Only the first two report to `core/net/RtspHealth`; a
  stall is the network's fault, not the camera's.
  `RtspHealth` is keyed **per camera**, unlike `Go2rtcHealth`. go2rtc is one shared service, so one
  failure predicts all; each camera is its own RTSP server, so a global verdict would let one
  powered-off camera downgrade the whole fleet for the session.
  Transport is **TCP-interleaved** (`setForceUseRtpTcp(true)`): the camera is reached over the
  tailnet as often as the LAN, and RTP-over-UDP needs its own ports to survive that path.
  Two constraints worth knowing: cameras allow only a handful of concurrent RTSP sessions (Frigate
  holds the sub stream, go2rtc the main, each viewing phone one more — an over-budget open is
  rejected and steps down, by design), and off-LAN this needs the camera IPs routed onto the
  tailnet (per-camera `/32` subnet routes, not the whole LAN).
  **Cleartext:** verified against media3 1.10.1 that the RTSP module never consults
  `NetworkSecurityPolicy` (raw `java.net.Socket`), so `cleartextTrafficPermitted="false"` stands
  unchanged — see CLAUDE.md for what to do if that ever changes.
- **Devices v3 — the control deck** (`ui/devices/` + `core/logic/ControlDeck.kt`, 2026-08-08):
  the tab regrouped by FUNCTION and ordered by IMPORTANCE, because the Rooms tab already
  browses by place — Rooms answers "where things are", Devices answers "what can I do".
  Fixed section order (the hierarchy IS the design, no per-user section config):
  Needs attention (offline / battery ≤20%, absent when healthy) → Pinned → Security (locks
  then alarm, full cards — a lock never becomes a tile) → Lights & switches (2-col
  `ControlTile` grid, room as caption) → Climate & fans → Covers → Media → one Cameras
  summary row and one Sensors summary row, each opening a sheet built from the shared
  device-group components. The v2 chip filter is GONE — sections are the categories.
  Search still bypasses everything (flat `searchResults`, one tap to detail); long-press →
  rename / pin / hide everywhere, persisted in `util/DevicePrefsStore` (DataStore) with the
  hidden-devices shelf. Display names resolve rename → override → non-junk friendly_name →
  registry device name (`core/logic/Resolve.kt displayName`).
  **Domain contract** (`core/logic/Cards.kt NON_DEVICE_DOMAINS`, lockstep with `src/lib/ha.ts`):
  besides automations/scripts/scenes/people/zones/sun, the hub also excludes `button`/`event`/
  `image` (since 2026-08-07) — measured against the live house they were ~73 of 305 rows and all
  of it device plumbing (PTZ nudges, scene-controller event streams, AI snapshots). They stay
  reachable through entity-detail **Diagnostics**, whose sibling filter readmits
  `NON_DEVICE_DOMAINS` on both platforms (`EntityDetailViewModel.diagnostics` /
  `entityStore.useDeviceDiagnostics`).
  **The READONLY tier is device-aggregated** (since 2026-08-07, Android): entities sharing an HA
  device-registry id (≥2 in a room's read-only tier) collapse into one `ReadonlyItem.Group` row —
  a camera and its 12–18 sensor spray become one "Nursery Camera — N sensors · M active" row that
  opens a member sheet. Invariants worth knowing before touching `DeviceSections.kt`: a non-blank
  search query **bypasses grouping** (hits render flat, one tap from detail); user-hiding
  partitions **before** sectioning, so groups self-heal as members hide and long-press on a group
  bulk-hides its members (restorable individually from the shelf); the chip filter goes through
  the pure `filterSections` helper, which filters group members, degrades one-survivor groups to
  singles, and recomputes `total`/`activeCount` so room summaries never lie; `total` counts a
  group once (room summaries say what the *list shows*, not the entity count). FEATURED/CONTROL
  tiers stay per-entity — an inline control is never buried in a group.
  **The same chain applies to Rooms → area detail** (since 2026-08-08): `AreaDetailViewModel`
  historically filtered on `isPrimaryEntity` alone, which was survivable while the camera
  devices had no HA area — the moment they were assigned rooms, each camera poured ~30 primary
  entities (PTZ buttons, snapshot images, sensor spray) into its room's detail. It now applies
  `NON_DEVICE_DOMAINS` + user-hidden + renames and device-groups its read-only tail via the
  same `buildDeviceSections` (constant `areaOf`, one section). `DeviceGroupRow`/`DeviceGroupSheet`
  live in `ui/components/DeviceGroup.kt`, shared by both screens, so the two renderings of a
  grouped device cannot drift. Invariant: **wherever read-only entities render, they group by
  device and honor the full visibility chain.**
  **Pinned rail** (since 2026-08-07, closing AUDIT-2026-08 §7.1): the tab opens with a "PINNED"
  section — the user's ordered shortcuts, long-press → Pin/Unpin/Move up/Move down. Stored as a
  JSON string-array in `util/DevicePrefsStore` (`pinned_entities`; order matters, hence not a
  string-set); `null` = never customized = the `config/Favorites` seed, with the first edit
  materializing it (`core/logic/Pins.kt`, exact web `prefsStore.ts` semantics). Pinned devices
  **also stay in their rooms** — the rail is a shortcut, not a re-org, so room summaries never
  lie. Hide wins over pin (the hidden partition runs before the rail is built), and the rail
  hides while searching or chip-filtered, same gating as the hidden footer.
- **Control interaction model** (`ui/components/`): the hero domains render premium PULSE
  widgets, each committing **exactly one service call per gesture** with live-local preview
  (no mid-drag calls, so no `awaitEcho` plumbing):
  - **Lights — `LightPillar`**: the whole surface is the dimmer. A warmth-tinted glow fill
    (scalar warmth from `core/logic/LightFeel.kt lightWarmth` — kelvin/rgb attrs → 0..1, lerped
    between effort/streak channels in the composable so no raw color originates in logic) rises
    to the level; drag anywhere to dim with a haptic tick per quarter (`tickCrossed`), tap to
    toggle, release commits once (`dimCommit` — the floor sends a real `turn_off`). Optimistic.
    Non-dimmable lights are tap-only (`isDimmableLight` still gates, same as the old slider).
  - **Switches — `RockerSwitch`**: full-width spring rocker (tap anywhere or drag the thumb past
    the midpoint), optimistic via `rememberOptimisticOnOff`.
  - **Locks — `LockVault`** around the unchanged `SlideToAct`: the drag is still the
    confirmation and the thumb still holds a spinner until HA's echo (non-optimistic, per
    invariant 1). The vault adds the state's voice from `core/logic/LockState.kt lockVaultView`:
    a deadbolt glyph that throws **only on the echoed `locked`**, recovery secure glow, a
    streak jammed frame with a reject buzz (jam leaves the glyph visibly stuck partway), and a
    charge shimmer while pending. The settle-thunk confirm haptic lives here too.
  - **Climate — `ThermostatDial`**: a 270° arc (geometry in `core/logic/Thermostat.kt`) dragged
    along the ring, tinted by `hvac_action` (streak heating / effort cooling), setpoint in mono
    type with the measured "now" beneath; −/+ 48dp steppers remain as precise controls; a
    bottom dead-gap rejects stray touches; no target → read-only dial.
  - **Alarm — `ArmSegments`** (animated channel pill, still plain taps with per-segment pending
    spinners, non-optimistic) and **media — `MediaTransport`** (instrument-disc transport).
  Fans/covers keep the original compact controls (`ToggleRow`/`LevelSlider`/`CoverButton`).
  Gesture→value math is pure and unit-tested (`LightFeelTest`, `ThermostatTest`,
  `LockStateTest`). Haptics route through the `Haptics` vocabulary (`rememberHaptics()`) —
  actuation tick, threshold buzz on ticks/steps/commit points, reject buzz with the failure
  snackbar and on entering a jam.
- Cleartext HTTP is **off** — `network_security_config.xml` sets `cleartextTrafficPermitted="false"`,
  so the app reaches HA only over HTTPS via the Tailscale Serve TLS front
  (`https://<host>.ts.net:8443`). This was once deliberately `true`, because the HA host could be a
  bare `100.x` Tailscale IP that a scoped `<domain-config>` cannot match; TLS on the proxy removed
  that constraint and the flag was flipped. A **debug-only** override
  (`src/debug/res/xml/network_security_config.xml`) re-permits cleartext to `10.0.2.2`/`localhost`
  for the instrumented mock-HA and never ships in a release APK. Reverting to a plain-HTTP host
  means re-opening cleartext globally — a scoped domain-config still cannot match a bare IP — so it
  is a discussion, not a manifest tweak. The direct-camera RTSP tier is unaffected: media3's RTSP
  module connects via raw `java.net.Socket` and never consults `NetworkSecurityPolicy` (verified
  against 1.10.1).
- **Push** (`push/`) — self-hosted **ntfy**, no FCM/Google. `NtfyPushService` is a `specialUse`
  foreground service holding one streaming connection to `<base>/<topic>/json`; each frame is
  parsed (`NtfyMessage`, pure/tested), classified (`PushRoute.kindOf`: doorbell/alarm/generic),
  and raised via `PushNotifier` (per-kind channels). **Tap → deep-link:** a doorbell notification's
  `click` URL carries `?camera=camera.<base>`; `PushRoute.cameraOf` extracts it, the tap intent
  carries it (`EXTRA_CAMERA`), and `PushNav` (an app-scoped bus) hands it to the nav shell —
  which brings Home forward (`onNewIntent` covers a warm tap) and opens that camera's lightbox.
  A specific camera opens in an overlay, not a NavHost route, which is why this goes through
  `PushNav` rather than a start destination. Off by default — opt in from Settings, which requests
  `POST_NOTIFICATIONS` and offers the **battery-optimization exemption** (One UI dozes long-idle
  foreground services); `PushSettings` (DataStore) persists it; `BootReceiver` restarts the
  listener after a reboot only if enabled. The server side (ntfy Deployment + the HA doorbell/alarm
  automations that publish to it) lives in the `hawksnest-automation` repo (`docs/ntfy-push.md`).
  On-device runtime (delivery with the app closed, battery, reconnect, the tap deep-link) is the
  one part unit tests can't cover — smoke-test it on the phone.
- **Launcher shortcuts** (`shortcuts/`) — long-press the app icon for **Lock up / Arm away /
  Arm home**. Published *dynamically* from the entities that exist rather than as static XML,
  for the same reason widgets ask which device they control: entity ids are per-install. The cost
  is that they appear only after the first connect, which beats a shortcut that silently fails.
  **Nothing that unlocks or disarms is offered, and that is a security decision.** A launcher
  shortcut is one tap with no confirmation and no app in the foreground; locking and arming fail
  safe, unlocking and disarming do not — which is exactly why the in-app control makes you slide
  and the widget makes you tap twice. `core/logic/shortcutsFor` owns that rule and is tested on
  it. Taps route through `ConnectionManager.control` like any other user action (same pending
  state, same failure snackbar), and `MainActivity` consumes the extra so a configuration change
  cannot re-fire it.
- **Appearance** — `HawksnestTheme` has always taken `darkTheme` as a parameter and the light
  palette shipped with the V1 gates; what was missing was a control. `core/logic/ThemePref`
  (Dark/Light/System) persists in `DevicePrefsStore` and `MainActivity` resolves it via the pure
  `resolveDarkTheme(pref, systemIsDark)`. **Android defaults to System, web defaults to Dark** —
  a deliberate divergence: web has never had another default, while Android has always followed
  the OS, and silently flipping an installed app's appearance to match a constant is worse than
  the inconsistency. `ThemePref.parse` is tolerant of junk, because a bad stored value would
  otherwise make the picker unreachable.
- **Camera chrome stays OFF the picture.** Controls live in the row above the frame, on black.
  This was tried the other way on 2026-08-03 — view controls as 30dp buttons overlaying the video
  — and reverted after one look on a device: too small to hit, competing with a bright daylight
  frame, and drawing a camera name over the one the camera already burns in. Swipe-to-switch went
  with it in favour of the `CameraSwitcher` dropdown. Two things were kept from that revision: the
  96dp scrubber, and chrome in **fullscreen only** (`FullscreenChrome`), where the row is
  off-screen and the previous behaviour lost Mute, Talk and Reply exactly when the picture was
  biggest.
- **Quick replies** — prerecorded messages played out of a camera's speaker, Ring-style. **The
  audio never touches the phone.** `sendQuickReply` POSTs to go2rtc's
  `/api/streams?dst=<camera>&src=ffmpeg:/config/replies/<file>#audio=pcmu`, which pushes a file
  from go2rtc's own config volume into the camera's audio backchannel. The obvious alternative —
  substituting a file for the microphone inside `TalkButton`'s WebRTC session — needs a custom
  `AudioDeviceModule` and would only ever work on Android; this needs no mic permission, no peer
  connection and no negotiation, and the web app gets it free. PCMU because that is the codec the
  cameras advertise on their sendonly track. `quickReplyPath` (pure, tested) does the encoding:
  the source contains `:`, `/` and a `#` fragment, and a raw `#` truncates the query at the codec
  directive — a failure invisible from the app. Gated on `canPlayReplies(canGo2rtc)`, the same
  signal the live tier uses, and **fails closed**: unknown means no button. The result is always
  shown, because a reply that fails quietly leaves the user believing they spoke to someone at the
  door. Audio lives in the `go2rtc-replies` ConfigMap in `hawksnest-automation`; the filenames in
  `QUICK_REPLIES` and that ConfigMap must agree. **The audio carries 1.5s of leading silence and
  that is load-bearing** — the camera's speaker takes a moment to open and discards whatever
  arrives first, so unpadded messages played only their tail. It is not a pacing problem: a
  6-second file keeps its ffmpeg child alive ~5.2s, so go2rtc already pushes at realtime (and
  1.9.14 rejects every way of passing `-re` through the source string).
- **Crash capture** (`crash/`) — an uncaught-exception handler installed **first** in
  `HawksnestApp.onCreate`, before anything else can throw. Writes a scrubbed report to
  `filesDir/crashes/` synchronously and then **chains to the previous handler** so the process
  still dies normally; swallowing it would leave a frozen app and no "keeps stopping" dialog.
  Nothing is sent from the dying process — `CrashUploader` publishes pending reports on the *next*
  launch, over the ntfy endpoint push already uses, and only when push is enabled (someone who
  turned notifications off did not agree to "except crashes"). Reports are marked sent only on a
  successful POST, so an outage retries rather than dropping. Storage is bounded to the 10 most
  recent, which is also the blast radius of a boot loop. Visible in **Settings → Diagnostics**;
  the ntfy line names the first `com.hawksnest.` frame rather than the framework frames above it.
  **`core/logic/scrubSecrets` runs before anything is written or sent** and is the load-bearing
  piece — a report reaches disk, a screen, and a subscribable topic, while this app holds an HA
  token that opens the front door and RTSP camera credentials. It redacts by *shape* (URL
  userinfo, JWTs, bearer headers, token-ish query params) rather than by knowing the values, and
  is tested against each. Deliberately **not** a Sentry/GlitchTip client: self-hosting was the
  plan (ROADMAP2 T1 #7) but wants ~1.5–2 GB beside the door locks and the NVR, and with
  `isMinifyEnabled = false` these traces need no symbolication. `CrashUploader` is the seam to
  swap if that changes.
- **Home-screen widgets** (`widget/`, Glance/RemoteViews) — a light toggle with dim steps, an
  on/off paddle, a lock, the alarm panel's Off/Home/Away, a read-only room temperature, and a copy
  of the wall scene controller. Six `GlanceAppWidgetReceiver`s so the launcher lists them
  separately; one shared `WidgetConfigActivity` (it learns which widget it is configuring from the
  provider that launched it) picking an entity from `GET /api/states`.
  - **The temperature widget names itself after the ROOM**, not the sensor. Lamps and locks are
    named for where they are ("Nursery Lamp"); a sensor is named for what it is, so this widget
    shipped titled "Temperature Humidity XS Sensor …". The room comes from HA's **area registry,
    resolved at configuration time and stored with the widget** — it cannot be looked up at draw
    time, because the registries are WebSocket-only and the widget process deliberately speaks REST
    (below). The config screen is an ordinary activity with the app's socket, so it is the only
    place that can see areas at all; the field is prefilled from HA and editable, since plenty of
    installs never assign areas. Falls back to the sensor's own name (`temperatureTitle`) rather
    than inventing one. It draws the **shared `WidgetHeader`** like every other widget — it used to
    hand-roll a plain name row, which is precisely why it looked like the odd one out.
  - **The temperature widget is the read-only one**, so none of the pending/confirm/echo machinery
    below applies to it. It colours its reading by four bands — cold / comfortable / warm / hot —
    split on **three thresholds stored per widget instance**, because a nursery and a garage
    disagree about what comfortable means. Warm and hot are separate on purpose: in a nursery,
    warm is a nudge and hot is a problem, and folding them into one orange "not ideal" would make
    the urgent case stop reading as urgent. **Hot is the one band with no PULSE channel** — the
    four channels are blue/violet/orange/green and none is red — so it wears the app's *error*
    colour, on the number and on the panel rim (`widget_panel_alert`), which is the honest
    semantic for the only state that means "deal with this now". Thresholds are entered in a second config step after the picker, in the
    **sensor's own unit**: nothing converts, so a °C household types °C and the comparison, the
    display and the stored pair all stay in that unit with no flag anywhere. It takes the *light's*
    staleness stance, not the lock's — an old reading is still shown with its age, because a room's
    temperature doesn't change the way a door does and "70° an hour ago" is still useful, whereas
    "Locked" an hour ago is dangerous. The band also drives a word (`Comfortable`/`Cold`/`Warm`) so
    colour is never the only signal. Its picker filters `sensor` by `device_class: temperature` —
    without that, the huge `sensor` domain buries thermometers under every battery level and fps
    counter in the house.
  - **They do not use the WebSocket stack, deliberately.** `HaSource` is live-socket-only — no
    on-demand fetch, no entity cache — and a widget is drawn by a broadcast into a process that
    may have just been created and will be killed again shortly. Standing up the socket (auth
    handshake, three registry loads, reconnect loop) to answer "is the door locked?" costs seconds
    and battery for one reading. So `widget/data/WidgetHaClient` speaks HA's REST API directly
    (`GET /api/states[/<id>]`, `POST /api/services/<domain>/<service>`, bearer token from the same
    `CredentialStore`), the way `HaSource` already does for Frigate and automation config. Every
    call has a hard timeout and every failure becomes a `WidgetBlocker` the widget draws, never an
    exception.
  - **Echo without a stream.** `ControlGate`'s "wait for HA to react" becomes polling:
    `widget/data/WidgetEcho` re-reads the entity every second until the state *settles* somewhere
    other than where it started, within the same 30 s `ControlGate` allows. It polls *through*
    transitional states (`locking`, `arming`) rather than stopping at them — in-app the socket
    carries the rest of the story, but a widget that stopped there would sit on "Locking…" until
    something else redrew it. Clock and sleep are injected, so the timeout and jam cases are
    millisecond unit tests.
  - **Staleness is the widget-only hazard** (`core/logic/WidgetModel.kt`, all pure + tested). A
    widget is a picture drawn at an unknown time by a process that may since have died, so lock
    and alarm readings carry a 60 s expiry (`securityStateFresh`); past it the widget renders
    "Checking…" and refetches rather than repeating itself. This extends invariant 2's mask: on
    any failed fetch the stored security reading is dropped (`maskState`), the same rule
    `maskSecurityStates` applies in-app when the socket drops. Everything else is exempt — a lamp
    or a thermometer drawn wrong is cosmetic — so it renders from cache with its age shown once
    past 15 minutes. Persisted pending and confirm markers expire the same way, so a process killed
    mid-poll can't strand a spinner.
    **Which kinds those are is two named predicates, not a condition repeated per call site.**
    `widgetIsOptimistic` (draw the command now, reconcile after) and `widgetKeepsStaleReading` (may
    show a reading HA can't confirm) live beside the view-models and are unit-tested. They replaced
    four copies of `kind == WidgetKind.LIGHT` in `WidgetRepository`, which had quietly got the
    temperature widget wrong: its own view-model is built to show an expired reading with its age,
    but the repository blanked it on any failed fetch, because the condition named a kind instead
    of the rule. A condition spelled as a list of kinds acquires a bug every time a kind is added;
    one spelled as the rule does not.
  - **The expiry alone isn't enough, because a drawn widget is pixels.** Nothing redraws the home
    screen when a value ages out, so a frame that said "Locked" when it was true can still be
    there an hour later. Rather than schedule redraws forever (a permanent background poll, for a
    surface only reachable on the tailnet), lock and alarm widgets **always print the clock time
    the reading was taken** next to the state — "Locked · 10:42". A persisted frame then dates
    itself and can't lie, at zero ongoing cost.
  - **Destructive commands take two taps.** Unlock and disarm arm a confirmation that lapses after
    5 s; lock and arm are one tap. Glance can draw neither `SlideToAct` nor a drag, so the confirm
    tap is the substitute for the deliberate gesture those controls exist to require. For the same
    reason the dimmer is discrete steps rather than a fake slider — each step still commits
    through `dimCommit`, one service call per gesture, as `LightPillar` does on release.
  - **The dim steps walk a ladder (`WIDGET_DIM_STOPS`), not a fixed percentage.** A fixed step is
    wrong at both ends: the eye reads brightness roughly logarithmically, so 80→65 is barely
    visible while 20→10 halves the room. The stops are tight at the bottom and wide at the top,
    like a physical dimmer's gearing. A read-only `LinearProgressIndicator` under the name shows
    the level; it is deliberately not tappable, because a ~250dp-wide widget split into enough
    zones to beat the step buttons would have ~20dp targets.
  - **A widget tap creates a process, and that is a constraint on the whole app, not on `widget/`.**
    `Application.onCreate` runs on every process creation, so anything it does has to be legal from
    the background. It was starting the ntfy foreground service unconditionally, which Android 12+
    refuses with a `ForegroundServiceStartNotAllowedException` — thrown from a bare `CoroutineScope`
    and therefore **fatal**. The result was that tapping any widget with the app closed killed the
    process before the widget's own service call went out: the widget looked dead and the light
    never moved. `NtfyPushService.start` now swallows that refusal (it is an `IllegalStateException`
    subclass, so no API guard is needed) and `MainActivity` retries from the foreground, since
    `Application.onCreate` will not run again in that process. **Anything added to app startup must
    be safe to run in a process the launcher created for a widget broadcast.**
  - **A widget's control must never take its direction from the widget's own reading.** The switch
    widget's compact tier used to collapse to a single toggle labelled with whichever direction the
    switch was not already in. That is the ordinary way to draw a toggle and it is wrong here:
    nothing redraws the home screen when someone flips the physical switch, so the reading behind
    that decision can be arbitrarily old — and when it is, the one control on offer points the
    wrong way and the state literally cannot be changed from the phone. Both directions are now
    drawn at every size (side by side when there is no height to stack), which is correct however
    stale the widget is, and the confirming read after any tap repairs the reading as a side
    effect.
  - **The switch widget is a paddle, and exists because the domain lies.** `light.*` does not mean
    dimmable: Z-Wave exposes the Inovelli VZW30-SN — an on/off switch — as a Multilevel Switch, so
    HA reports `supported_color_modes: ["brightness"]` and the light widget dutifully offers dim
    steps to hardware with no dimmer. `switchWidgetView` therefore **never consults
    `supported_color_modes` or `brightness`**: the kind is the promise, and the owner picking a
    switch widget is a better signal than the entity's own description of itself. `turn_on` is sent
    bare so HA restores the device's last level, which is what pressing the top of the physical
    paddle does. It draws **two stacked halves — On above, Off below — each always present and
    always tappable in the direction it names**, rather than the light's single self-describing
    toggle: a paddle has no "what will this tap do?" problem, so the state can be carried by which
    half is filled, and a half that moved or vanished as the state changed would be worse than a
    wasted tap. Compact has no room for two 48dp targets, so it collapses to one full-height
    toggle. Two size buckets, not the light's four — `compactNamePlacement` is INLINE for SWITCH at
    every width, since "On"/"Off" never need abbreviating, so a width bucket would be a layout
    nothing could select.
  - **The scene pad copies a piece of hardware, so its layout was measured, not designed**
    (`core/logic/ScenePadModel.kt`, `widget/ui/ScenePad.kt`). A Zooz ZEN32 is a large relay key
    across the **top** with four small keys in a 2×2 grid **beneath** it and an LED pinhole in each
    key's **upper-left** corner — checked against the hardware on 2026-08-01, because the design
    note it was built from had the relay at the bottom and would have inverted every constant.
    **The keys carry no labels**, as the plate doesn't: four ellipsised preset names in ~55dp
    squares would look nothing like the device, so the live preset is named once in the header and
    each key's preset becomes its content description. All five keys wear the same face and only
    the **LED** changes — lighting the key itself would mean tinting a button drawable in a channel
    colour, which would fight the lamp sitting on it.
  - **It is the one widget with two entities, and only one of them is read.** It *draws* the WLED
    preset selector (whose state is the live preset, which is what lights one LED) and it *drives*
    the controller's relay, stored as `COMPANION_ENTITY_ID` and never read. A companion-targeted
    command (`ActionTarget.COMPANION` → `WidgetRepository.actOnCompanion`) deliberately skips the
    optimistic draw, the pending marker and the confirming read: all three are about the entity on
    screen, and a spinner started here would wait forever on an echo from an entity nothing polls.
    The cost is that the relay key shows no state; giving it one means holding a second reading per
    widget, which changes the persisted key shape and belongs in a change that does only that.
  - **The four presets are configuration, not something HA reports.** The obvious design — take
    them from the select's own `options` — does not survive contact: a WLED preset selector carries
    every preset on the device (twelve, here) and which four the wall keys fire lives in an HA
    automation the widget cannot read. So they are per-instance, picked from the live `options` on
    the config screen, prefilled with the first four rather than with any particular name — the
    widget layer contains **zero** device-specific entity ids or preset names, and a default right
    for one household would be four wrong guesses everywhere else. The **LED colours** are
    prefilled with a real plate's (`ZEN32_DEFAULT_LEDS`, read off Z-Wave configuration parameters
    6-10) on the opposite reasoning: a wrong colour is cosmetic, a wrong preset fires a scene.
  - **Known divergence, recorded rather than fixed:** a physical key emits a Z-Wave Central Scene
    event that an automation turns into a service call; the widget makes that call directly,
    because Central Scene notifications are generated by the device and HA has no service to
    synthesise one. Identical today, silently different the day that automation grows a second
    action. The durable fix is an HA script per key as the single definition of what the key
    *means*, with both pointed at it — a reconfiguration, not a rewrite.
  - **`LedColor` is names in `core/logic`, values in `widget/ui`** — the same split as `Channel`,
    for the same reason: the logic layer stays Compose-free and testable, and a widget is drawn in
    whichever theme the launcher wears without a chance to redraw, so each colour must resolve to a
    day/night pair at draw time. It is also the one place in the app that deliberately does **not**
    route through PULSE tokens: these are a reproduction of one device's indicator colours, and
    pinning them to the palette would let a theme change quietly stop the widget matching the wall.
    A resting LED is blended toward the panel rather than drawn at reduced alpha, because Glance
    tints through `setColorFilter`, which discards alpha — a translucent tint comes back opaque.
  - **The light picker offers `light` only; the switch picker takes both domains back.** The light
    briefly took both, on the theory that relay-style lights land in `switch` — but here `switch.*`
    is overwhelmingly ring-mqtt camera plumbing (live/event streams, motion toggles, sirens), which
    buried the real lights. The app keeps the domains apart too (`Cards.kt`). The switch widget
    needs both, because a relay lands in either depending on the integration, and takes the domain
    back safely by filtering on the entity's **platform** (`ring`/`mqtt`, the constants
    `dedupeRingMqtt` already uses) rather than a name-suffix denylist — that drops the whole
    integration instead of guessing at names, and with no registry it degrades to unfiltered rather
    than to wrong.
  - **`WidgetConfigActivity` is the one part of this feature that is not REST-only**, and
    deliberately: it is an ordinary activity in the app process, so it can use `ConnectionManager`
    and with it HA's **entity registry** — which REST cannot see. That buys the picker the full
    `isPrimaryEntity` (hides `config`/`diagnostic` entities) and `dedupeRingMqtt` (collapses the
    Ring-vs-ring-mqtt twins), the same two filters the Devices list gets. `HaSource` loads the
    registries *before* it reports `CONNECTED`, so that status alone is proof the maps are
    populated; `DEMO` deliberately does not qualify, or the picker would offer fixture entities.
    It waits ~4s for the socket and otherwise falls back to the widgets' REST path. One code path
    handles both: `widgetCandidates` takes the registry maps as parameters, and each filter
    degrades to a no-op on an empty map (`isPrimaryEntity` falls back to the suffix denylist,
    `dedupeRingMqtt` returns its input), so the fallback list is worse but never wrong.
  - **Refresh** is on render, after every action, on tapping an error, and — while the app happens
    to be running — pushed from the live socket by `widget/WidgetLiveBridge` (throttled to one
    pass every 3 s). `updatePeriodMillis` is the platform's 30-minute floor and is cosmetic only.
    There is deliberately **no background polling**: it would cost battery for a widget that is
    only reachable on the tailnet anyway.
    **The render-triggered refresh must be throttled** (`WidgetRepository.lastFetchAt`, 10 s,
    in-memory): writing a widget's state redraws it, a redraw re-runs `provideGlance`, and
    `provideGlance` asks for a refresh — so an unthrottled refresh feeds itself forever at
    whatever rate the network allows. Failed fetches count toward the throttle too, or an
    unreachable HA becomes a retry storm.
  - **Two size tiers** (`sizeTier`, `WIDGET_FULL_MIN_HEIGHT_DP = 120`). The full layout needs a
    two-line header over a 48dp control; a one-row widget has nowhere near that, so compact
    collapses the header to one small line, drops the light's level bar, and lets the controls take
    the remaining height. **The XML rule that makes any of this reachable: `minResizeHeight` must be
    *below* `minHeight`.** It used to equal it, so launchers offered no vertical resize at all —
    which is why the widgets arrived oversized on a coarser home-screen grid and stayed that way.
    Within compact, the *name* is the only part of the header that may be moved, truncated, or
    dropped — never the state or its timestamp. `compactNamePlacement` spends the room it has:
    `INLINE` beside the state past `WIDGET_NAME_MIN_WIDTH_DP` (200dp), else `STACKED` on a second
    line past `WIDGET_COMPACT_TALL_BUCKET_DP` (80dp — the control below gives up the height), else
    `HIDDEN`. Only a widget both narrow *and* one row high hits `HIDDEN`; **narrow is not the same
    as no room**, and gating on width alone (the first fix for "the lock never says which door")
    left small-but-tall placements nameless. Inline, the header lays the state out at its natural
    width and weights the name, so a long name ellipsizes into the leftover instead of pushing the
    state off the line. Each decision needs a **matching size bucket** in the widget's
    `SizeMode.Responsive` set — under Responsive `LocalSize` reports the *bucket*, so a dimension
    the buckets don't distinguish cannot be seen at all.
  - **Styling** is `ui/glance/PulseGlanceTheme` (text and flat fills, from the app's own
    `ColorScheme`s via `glance-material3`) plus `res/drawable/widget_*.xml` for anything Glance
    can't express as a flat color. Glance has no border or gradient modifier, so the panel and the
    controls are **shape drawables**: a panel lit from above (`panelHigh` → `ink`) held by a 1dp
    hairline, engaged controls wearing PULSE's energy / hero / recovery gradients, and — the one
    idea borrowed from Remnant — a **channel-lit rim** in place of a shadow, so a locked door glows
    green at the edge and a jam glows orange. The drawables read `@color` from
    `values{,-night}/widget_colors.xml`, which is the drawable-side mirror of `ui/theme/Color.kt`
    and names the constant behind every value; the two must be edited together. Corner radii live
    in the drawables, which is also why they work below API 31 where
    `GlanceModifier.cornerRadius` is ignored.
- Suite membership: signed with the suite key (secrets are `HAWKSNEST_`-prefixed), released by
  `android-release.yml` on `android/**` pushes, tagged `android-vX.Y.Z` (clear of web `v*`).
  Managed by the Dragonfly hub for updates — but **no** SuiteConfigReader/AppAuth (nothing to
  broker; don't add them without a real reason).

## 3. Deployment (`deploy/`)

The web app ships as an nginx pod in the **same k3s cluster/namespace as HA itself** (cluster
owned by the sibling `hawksnest-automation` repo), NodePort 30080, exposed to LAN/Tailscale via
**Tailscale Serve `:8443` → host `8090` → on-disk `socat` systemd units in the WSL `Dragonfly`
distro → NodePort 30080** (changed 2026-07-22; the older netsh portproxy scripts that ran at logon
are retired, and `deploy/windows/portproxy-hawksnest.ps1` is kept only for the NAT-mode case —
it is broken under mirrored WSL networking). Host runbook: `C:\Code\OPERATIONS.md` §1.2 / §6.

nginx reverse-proxies `/api` to HA so the browser is same-origin. **Invariant: every HA-proxied
location must CLEAR `X-Forwarded-For`/`X-Forwarded-Proto` (`proxy_set_header … ""`)** — with
`use_x_forwarded_for` and an untrusted proxy IP, HA 400s every request (this killed all camera
frames once). Not-adding XFF was enough behind a plain portproxy, but the TLS front (Tailscale
Serve, `:8443`) injects XFF and nginx passes inbound headers through, so it must be actively
stripped. Alternative: trust the flannel pod CIDR in HA — never half-do it.

`deploy.test.ts` asserts the deploy contract (nginx config, Dockerfile, NodePort) — **if you
change deploy files, that test is the spec**; update both together.

## Testing map (all real seams covered without real hardware)

- Loading/empty states across the secondary screens use the shared `Skeleton` (web) / `Modifier.shimmer`
  (Android): Rooms (connecting → room-card skeletons, empty state), History (timeline-row skeletons),
  and the camera/history-chart uses from earlier. Automations toggles render optimistically and
  "Run now" flashes a confirmation (the effect is otherwise invisible).
- `npm run test` — vitest: screens, stores, protocol, `deploy.test.ts`.
- `npm run test:e2e` — Playwright against **`mock-ha/`**, a scriptable fake HA speaking the real
  WebSocket protocol (auth, reconnect, doorbell, lock pending/jam/rejected flows) with a
  `/__scenario` API. The same mock serves Android instrumented tests
  (`scripts/android-emulator-test.sh`, needs KVM).
- The home-screen widgets are covered by unit tests only, and deliberately so — everything that
  can be got wrong there is pure: the security expiry and masking rules, pending/confirm lapse,
  the echo poller against a fake clock (`WidgetModelTest`, `WidgetEchoTest`), and the REST client
  against MockWebServer for 200/401/404/5xx/dead-host/garbage-200 (`WidgetHaClientTest`). What
  isn't covered is the launcher hosting itself — placement, resize, and a tap surviving process
  death — which needs a real home screen.
- CI: `ci.yml` (typecheck/lint/test/build + kubeconform over the k8s manifests),
  `android-ci.yml` (unit tests + assembleDebug + the advisory **Sift** design audit — a sibling
  public repo checked out by CI; known to trip on the new Compose render, advisory-only).
- Known gap: the live camera pipeline (WebRTC web / LL-HLS Android) is only ever hand-tested —
  the mock serves no `web_rtc` camera. ROADMAP #4 wants at least a written per-release smoke
  checklist.

## Invariants (security-flavored — this app unlocks doors)

1. **Locks _and the alarm_ are non-optimistic UI** — pending until HA confirms (including on the
   home-screen widgets, which poll for the confirmation they can't stream), and a failed or
   silent call surfaces an error rather than doing nothing. Deliberate; the E2E suite pins it.
   Don't "fix" the lag. Web: the alarm arm/disarm behaviour is shared by the dashboard
   `SecurityStatusBar` and the `AlarmCard` via the `useAlarmControl` hook (tapped mode spins until
   HA reaches the requested state / `triggered`, a rejected call shows an error, and a safety-net
   timeout stops a spinner the panel never answers); `LockCard` carries the same pending/error +
   timeout contract. Android: the same contract lives in `ControlGate`, and locks render as
   `SlideToAct` — the pending wait *is* the thumb holding at the end of the track. The optimistic
   switches on lights/switches/fans are **not** a violation: the invariant covers security
   domains — locks and the alarm — only.
2. **Honest degraded offline (the refined no-stale-state invariant).** Hawksnest still has **no
   persistent entity cache** — nothing about entity state ever touches disk, and no command is
   ever queued. What was refined: after an **in-session** drop, non-security entities may keep
   rendering **dimmed + labeled** ("Reconnecting — as of HH:MM", controls disabled) for **≤120 s**
   (`GRACE_WINDOW_MS`), because a blank screen three seconds into a Wi-Fi blip is less honest than
   a labeled stale one; **lock and alarm state is never rendered stale, not even inside the
   window** — the store masks `lock.*`/`alarm_control_panel.*` to `unavailable` the moment the
   socket is lost (web `lib/offline.ts::maskSecurityStates` in `entityStore.setStatus`; Android
   `core/logic/Offline.kt` in `HaState.setStatus`), and the lock/alarm/security-bar surfaces
   additionally present an explicit "Unknown — offline". **The home-screen widgets extend this
   with an expiry**, because they have no session to be "in": a lock or alarm reading older than
   60 s is not rendered at all, and any failed fetch drops the stored reading outright
   (`core/logic/WidgetModel.kt`, `widget/data/WidgetState.kt::maskState`). Past the window — or immediately on a
   terminal auth error — the UI collapses to the full **OfflineState** (web
   `components/OfflineState.tsx`, Android `ui/components/OfflineState.kt`): no entity data at all,
   a "Last connected …" readout, a **Retry now** (web restarts the source; Android
   `Source.retryNow()` → `HaSource`'s `RetrySignal` skips the remaining 1 s→30 s backoff, whose
   next attempt is published as `nextRetryAtMs` for the live countdown), and a **passive
   reachability hint**: one bounded probe per backoff cycle (web: same-origin `fetch("/api/")`;
   Android: `core/net/ReachabilityProbe`, shared with Settings → Test) distinguishes "your home
   network is unreachable — check Tailscale" (transport failure) from "reachable but HA isn't
   answering" (any HTTP response). A first-ever connect has nothing stale to show, so it never
   enters the grace window. A successful reconnect's fresh snapshot replaces everything.
3. **Service worker: never cache `/api`, never touch the token.**
4. **nginx XFF rule** (above) — all-or-nothing.
5. Long-lived-token auth is the accepted Phase-0 posture; the upgrade path is TLS-then-OAuth
   (ROADMAP #1–2), in that order, web + Android together so the token story stays one story.
   Android hardens token-at-rest now: the LLAT is Keystore-encrypted (`util/TokenCipher`) and
   excluded from cloud backup / device transfer, so a copied credential file is useless off-device.
6. Unknown HA domains must render, not crash (`cards.ts` contract).

## Where to make common changes

- **New device type/domain**: `src/lib/cards.ts` mapping + a card component (web);
  `core/logic` + `ui/devices` (Android). Entity naming quirks → `config/overrides.ts`.
- **New home-screen widget**: a `WidgetKind` + its pure view-model in `core/logic/WidgetModel.kt`,
  a `GlanceAppWidget`/`Receiver` pair in `widget/`, the `when` in `widget/WidgetKinds.kt`, an
  `appwidget-provider` XML, and a manifest receiver. The data layer is domain-agnostic already.
  (Two worked examples: the **temperature** widget for one that carries extra per-instance
  settings, the **switch** widget for the smallest possible kind. The exhaustive `when`s make the
  compiler list every site you still owe.) Per-instance *settings* beyond the entity — like
  temperature's colour thresholds — are extra `WidgetKeys` written by `WidgetRepository.configure`,
  plus a second step in `WidgetConfigActivity` after the picker.
  Three things the compiler will *not* catch: `minResizeHeight` must be strictly below `minHeight`
  or the launcher offers no vertical resize; behaviour that varies by kind belongs in a named
  predicate in `WidgetModel.kt` (`widgetIsOptimistic`, `widgetKeepsStaleReading`), never a
  `kind == …` test written inline at the call site; and Glance's generated layouts have nesting and
  children-per-container limits that fail at **runtime**, so a widget with a deeper tree than the
  ones already shipped (the scene pad is the deepest) has to be placed on a real launcher before it
  can be called working — a green `assembleDebug` proves nothing about it.
- **HA protocol behavior**: extend `mock-ha/` first, write the failing E2E, then implement.
- **Deploy changes**: `deploy/` + `deploy.test.ts` together.
- **Automation-side features** (new sensors, Ring/Z-Wave config): wrong repo — that's
  `hawksnest-automation`; Hawksnest usually just renders the new entities via the domain mapping.
