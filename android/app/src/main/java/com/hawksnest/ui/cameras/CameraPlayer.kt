package com.hawksnest.ui.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.canReachSpeaker
import com.hawksnest.core.logic.NO_ZOOM
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.hawksnest.core.logic.ClipSelection
import com.hawksnest.core.logic.TimeWindow
import com.hawksnest.core.logic.defaultSelection
import com.hawksnest.core.logic.exportBounds
import com.hawksnest.core.logic.frigateCameraName
import com.hawksnest.core.logic.nudge as nudgeClip
import com.hawksnest.core.logic.setEdge as setClipEdge
import com.hawksnest.core.logic.RecordedBackend
import com.hawksnest.core.logic.RecordedSource
import com.hawksnest.core.logic.RingFootage
import com.hawksnest.core.logic.RingTimeline
import com.hawksnest.core.logic.isFrigateCamera
import com.hawksnest.core.logic.recordedBackendOf
import com.hawksnest.core.logic.chooseRecordedSource
import com.hawksnest.core.logic.clipContaining
import com.hawksnest.core.logic.clipSpanEndMs
import com.hawksnest.core.logic.FootageSpan
import com.hawksnest.core.logic.footageSpans
import com.hawksnest.core.logic.offsetInClipMs
import com.hawksnest.core.logic.TimeRange
import com.hawksnest.core.logic.retentionRange
import com.hawksnest.core.logic.vodPageFor
import com.hawksnest.core.logic.vodPositionMsInPage
import com.hawksnest.core.net.RtspHealth
import com.hawksnest.ui.home.CameraUi
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.delay

private const val DAY_MS = 24 * 3600_000L

/** How long a clip switch is held off while the user is actively scrubbing, so dragging across
 *  several clips doesn't fire a select_option + stream per clip. */
private const val SCRUB_CLIP_DEBOUNCE_MS = 300L

/** The Frigate/ring camera name backing a `camera.<slug>` logical id. */
private fun cameraNameOf(id: String): String = id.substringAfter('.', id)

/**
 * Ring-style camera player: live feed + a scrubbable 24h timeline of recorded events, an in-player
 * camera switcher, and transport controls. The playhead is null (live) or an epoch-ms time.
 * Dragging the timeline scrubs live: the playhead follows the drag and, when it's inside a kept
 * recording, the video seeks in real time (forward and reverse); releasing keeps playing from that
 * moment. The timeline shows **only playable recordings** (Ring-style: every block is watchable) —
 * ring's ~5 selector events, or Frigate/demo clip-bearing events. Mirrors the web `CameraPlayer`.
 */
@Composable
fun CameraPlayer(
    cam: CameraUi,
    cameras: List<CameraUi>,
    onSelectCamera: (CameraUi) -> Unit,
    viewModel: CameraPlayerViewModel,
    /** Frigate event to open on, from a tapped camera alert. Null = open live. */
    initialEventId: String? = null,
    modifier: Modifier = Modifier,
) {
    val cameraName = cameraNameOf(cam.id)
    // Which backend holds this camera's recordings — the port of the web's
    // `recordedBackendOf` split (core/logic/RecordedBackend.kt), replacing the raw
    // `eventSelectId != null` boolean that was doing three jobs. Frigate membership
    // is stamped on the HA entity by the frigate-hass-integration (fails closed).
    // Decided once per camera, like canWebRtc below: mid-view attribute churn must
    // not flip the recorded path under a mounted player.
    val backend = remember(cam.id) {
        recordedBackendOf(
            hasRingSelector = cam.eventSelectId != null,
            hasFrigateCamera = isFrigateCamera(viewModel.entity(cam.entityId)),
        )
    }
    val isRing = backend == RecordedBackend.RING
    // Named alongside isRing so the two platforms read identically at the clip-export gate below.
    val isFrigate = backend == RecordedBackend.FRIGATE
    // Frigate's own name for this camera, authoritative over the HA slug for URL building (see
    // Frigate.kt). Normally identical; when they are not, the slug 404s.
    val frigateExportName =
        remember(cam.id) { frigateCameraName(viewModel.entity(cam.entityId)) ?: cameraName }
    // Pin "now" once so the timeline doesn't slide under the user mid-session.
    val nowAnchor = remember(cam.id) { System.currentTimeMillis() }
    // How far back the timeline reaches. Ring stays at 24h — its recorded path is the last handful
    // of cloud events, so a longer span renders a mostly-empty timeline and pulls days of Ring
    // history for nothing. Frigate keeps continuous video, so its timeline spans the retention
    // Frigate actually reports (`record.continuous.days`) rather than a hardcoded day.
    val retentionDays: Double? by produceState<Double?>(null, cameraName, isRing) {
        value = if (isRing) null else runCatching { viewModel.frigateRetentionDays(cameraName) }.getOrNull()
    }
    // Read once per camera: the Frigate VOD player needs it on every media request.
    val mediaAuthToken: String? by produceState<String?>(null, cam.id) {
        value = runCatching { viewModel.haToken() }.getOrNull()
    }
    val window = remember(nowAnchor, retentionDays, isRing) {
        if (!isRing && retentionDays != null) {
            retentionRange(nowAnchor, retentionDays).let { it.startMs to it.endMs }
        } else {
            (nowAnchor - DAY_MS) to nowAnchor
        }
    }
    val (startMs, endMs) = window

    // Ring's OWN timeline via the ring-timeline service — real event times, real spans and directly
    // playable URLs. Preferred over the selector for ring cameras: the selector has no event times
    // at all (its blocks were plotted on fabricated 6-minute spacing) and yields nothing playable on
    // the wired cameras. Null = service unreachable / camera not one of its devices → selector path.
    // The nonce refetches: Ring signs its URLs for ~15 minutes, so a timeline left open goes
    // unplayable.
    var timelineNonce by remember(cam.id) { mutableStateOf(0) }
    // Which source we've already spent our one expired-signature refetch on.
    var refetchedFor by remember(cam.id) { mutableStateOf<String?>(null) }
    val recorded: RingRecorded by produceState(RingRecorded.NONE, cam.id, timelineNonce) {
        value = if (isRing) {
            runCatching { viewModel.ringRecorded(cam.name, cameraName, startMs, endMs) }
                .getOrDefault(RingRecorded.NONE)
        } else {
            RingRecorded.NONE
        }
    }
    val timeline: RingTimeline? = recorded.timeline
    // The 24/7 continuous track. Null (or empty) = no track — the battery cameras and the doorbell
    // record events only — which is a real answer, not a failure: there is simply no lane.
    val footage: RingFootage? = recorded.footage?.takeIf { it.continuous }
    // Both halves are signed on the same ~15-minute clock, so whichever URL dies first drives the
    // refresh for both.
    val expiresAtMs = listOfNotNull(timeline?.expiresAtMs, footage?.expiresAtMs).minOrNull()
    LaunchedEffect(expiresAtMs) {
        val expiry = expiresAtMs ?: return@LaunchedEffect
        // Refetch a minute ahead of the earliest expiry so every block on screen stays watchable.
        delay((expiry - System.currentTimeMillis() - 60_000).coerceAtLeast(5_000))
        timelineNonce += 1
    }
    // The continuous track as drawable spans (coalesced so a stitch seam doesn't read as a gap).
    // Ring's spans come off its timeline fetch; Frigate's are fetched below, pre-coalesced.
    val ringLane = remember(footage) { footage?.let { footageSpans(it.segments) } ?: emptyList() }
    // The Frigate continuous lane — where recordings actually exist, so the strip shows "you can
    // scrub anywhere here" instead of rendering blank between event chips. Empty until it
    // resolves — the lane just appears, nothing blocks on it. Mirrors the web CameraPlayer.
    // Keyed on the window too: it opens at the 24h fallback and widens to the real retention when
    // frigateRetentionDays resolves — without the key the lane would stay 24h on a 3-day strip.
    val frigateLane: List<FootageSpan> by produceState(emptyList(), cam.id, startMs, endMs) {
        value = if (isRing) {
            emptyList()
        } else {
            runCatching { viewModel.cameraFootage(cameraName, startMs, endMs) }.getOrDefault(emptyList())
        }
    }
    val footageLane = if (isRing) ringLane else frigateLane

    // Only real recordings make the timeline (Ring-style: every block is watchable) — Ring's own
    // events, ring's ~5 selector events, or Frigate/demo events that carry a clip.
    // Keyed on the window (same reason as the lane above): a Frigate camera's window widens from
    // the 24h fallback to real retention when frigateRetentionDays resolves, and events fetched
    // for the narrow window would silently leave days 2-3 chipless.
    val fallbackEvents: List<CameraEvent> by produceState<List<CameraEvent>>(emptyList(), cam.id, startMs, endMs) {
        value = runCatching {
            if (isRing) {
                viewModel.ringEvents(cam.eventSelectId!!, cameraName, startMs, endMs)
            } else {
                viewModel.events(cameraName, startMs, endMs).filter { it.hasClip }
            }
        }.getOrDefault(emptyList())
    }
    val events: List<CameraEvent> = timeline?.events ?: fallbackEvents
    // null playhead = live; reset to live whenever the camera changes.
    var playhead by remember(cam.id) { mutableStateOf<Long?>(null) }

    // Deep-linked from a tapped camera alert: seek to the moment that triggered it.
    // Events arrive asynchronously, so this waits for the list and fires ONCE —
    // keyed on the event so a later camera switch or refetch can't yank the
    // playhead back, and guarded so an event that isn't in the window (aged out of
    // retention, wrong camera) simply leaves the player live rather than seeking to
    // a fabricated time.
    var deepLinkSeeked by remember(cam.id, initialEventId) { mutableStateOf(false) }
    LaunchedEffect(initialEventId, events, deepLinkSeeked) {
        if (initialEventId == null || deepLinkSeeked || events.isEmpty()) return@LaunchedEffect
        events.firstOrNull { it.id == initialEventId }?.let { ev ->
            playhead = ev.startMs
            deepLinkSeeked = true
        }
    }

    var paused by remember(cam.id) { mutableStateOf(false) }
    // True while a timeline drag is in flight — clip switches debounce against it.
    var scrubbing by remember(cam.id) { mutableStateOf(false) }
    // The clip loaded in the player and its real media duration (clipId → durationMs), once known —
    // refines timeline containment + chip width for open-ended (`endMs = null`) ring clips.
    var loadedClip by remember(cam.id) { mutableStateOf<Pair<String, Long>?>(null) }
    var retryNonce by remember(cam.id) { mutableStateOf(0) }
    // Ring/go2rtc live is WebRTC (sub-second). Try it first; on failure, step down to HLS/MJPEG.
    // Decide ONCE per camera — not every recomposition — so a mid-view entity update (battery cams
    // churn their attributes) can't flip the transport off WebRTC and drop us to the stale snapshot.
    val canWebRtc = remember(cam.id) { viewModel.canWebRtc(cam.entityId) }
    var webRtcFailed by remember(cam.id) { mutableStateOf(false) }
    // A tier ABOVE HA WebRTC: WebRTC straight to the dedicated go2rtc (~1-2s, continuous RTP
    // rather than segmented HLS). Decided ONCE per camera, like canWebRtc.
    //
    // This used to gate on `isRing`, which was only ever a proxy for "go2rtc serves this" — true
    // while go2rtc served Ring cameras exclusively, and wrong the moment the Reolink main streams
    // were added. Reolink cameras fell to HLS and looked visibly jumpy. It now asks go2rtc what it
    // actually serves (core/net/Go2rtcStreams, the port of web's lib/go2rtc.ts), which is both
    // correct for Reolink and still skips the tier for cameras go2rtc has never heard of — the
    // 8-second watchdog stall the isRing gate had been incidentally preventing.
    //
    // null = undecided (the list is being fetched); see `wantsHls`.
    val canGo2rtc: Boolean? by produceState<Boolean?>(null, cam.id) {
        value = runCatching { viewModel.canGo2rtc(cameraName) }.getOrDefault(false)
    }
    var go2rtcFailed by remember(cam.id) { mutableStateOf(false) }

    // Camera audio starts muted (matching the web player); the MuteButton is the way
    // back. Unkeyed on purpose — switching cameras keeps the choice, like the web.
    var muted by remember { mutableStateOf(true) }
    // Low/High live quality. Low = the go2rtc `<name>_sub` stream (~1/20 the
    // bandwidth of the fixed-bitrate main), offered only when go2rtc lists it —
    // Ring cameras have no `_sub` and never see the toggle. While Low is selected
    // the RTSP-direct tier is bypassed too: that tier plays the MAIN stream
    // straight off the camera, which is exactly what a user on weak cellular is
    // trying to escape.
    var qualityLow by remember { mutableStateOf(false) }

    // Movement controls, if this camera has any. Resolved from the entity ids that
    // exist rather than derived from the camera name — see core/logic/CameraPtz.kt
    // (the stairway's Reolink device is named differently from its Frigate camera).
    val ptz by remember(cameraName) { viewModel.ptzControls(cameraName) }
        .collectAsState(initial = null)
    var showPtz by remember(cam.id) { mutableStateOf(false) }
    // Keyed on cam.id so switching cameras closes the sheet: the replies would still be addressed
    // to the camera you were looking at a moment ago, which is a message played in the wrong room.
    var showReplies by remember(cam.id) { mutableStateOf(false) }

    // Pinch-zoom over the picture. Keyed on cam.id so switching cameras starts unzoomed —
    // a magnified corner carried over to a different room is disorienting and reads as a bug.
    var zoom by remember(cam.id) { mutableStateOf(NO_ZOOM) }
    // Fullscreen is NOT keyed on cam.id: switching cameras while fullscreen should stay
    // fullscreen, which is what the camera switcher in the overlay is for.
    var fullscreen by remember { mutableStateOf(false) }
    FullscreenEffect(fullscreen)

    // Sound behaves like a video, not a phone call — see CameraAudio.kt. The rocker adjusts media
    // volume the whole time a camera is open; focus is taken only once the owner unmutes, so
    // merely opening a camera never interrupts what they were listening to.
    CameraVolumeKeys()
    CameraAudioFocus(active = !muted)

    val subAvailable: Boolean by produceState(false, cam.id) {
        value = runCatching { viewModel.canGo2rtc("${cameraName}_sub") }.getOrDefault(false)
    }
    val useSub = qualityLow && subAvailable
    val go2rtcSrc = if (useSub) "${cameraName}_sub" else cameraName

    // ABOVE go2rtc: RTSP straight to the camera, the same thing the vendor's own app does. Null
    // unless the user configured an account + this camera's IP in Settings, so an unconfigured app
    // behaves exactly as before. Reads local DataStore only — no network, effectively instant.
    val rtspUrl: String? by produceState<String?>(null, cam.id) {
        value = runCatching { viewModel.rtspUrlFor(cameraName) }.getOrNull()
    }
    var rtspFailed by remember(cam.id) { mutableStateOf(false) }
    // Snapshot the breaker at mount (mirrors canWebRtc) so a verdict landing mid-view can't yank
    // the transport out from under a session that is playing fine.
    val rtspHealthy = remember(cam.id) { RtspHealth.maybeAvailable(cameraName) }
    val canRtsp = rtspUrl != null && rtspHealthy && !rtspFailed

    // Resolve the HLS stream URL only once the HLS tier could actually render — NOT eagerly on
    // open. `camera/stream` makes HA spin up a stream pipeline, which on a battery camera wakes
    // it / competes for its single live session in parallel with the WebRTC negotiation above it
    // on the ladder (the request itself is bounded at 15s in HaSource). Every tier above must be
    // exhausted before we resolve HLS — and "undecided" is not exhaustion, so neither a pending
    // go2rtc list nor a pending RTSP lookup may resolve it.
    val wantsHls = !canRtsp && (canGo2rtc == false || go2rtcFailed) && !(canWebRtc && !webRtcFailed)
    val liveUrl: String? by produceState<String?>(null, cam.id, wantsHls) {
        value = if (wantsHls) viewModel.liveStreamUrl(cam.entityId) else null
    }

    val isLive = playhead == null
    val headTime = playhead ?: endMs

    // --- Clip export ---------------------------------------------------------------------
    // Note what is NOT here: none of this feeds `vodPage`, the signed VOD URL, or the VideoPlayer
    // keys. Entering clip mode must be invisible to the playback stack — a selection that re-keyed
    // the player would re-prepare (and re-sign) the VOD on every nudge. See ARCHITECTURE.md's
    // player-identity trap.
    val context = LocalContext.current
    var clipSel by remember(cam.id) { mutableStateOf<ClipSelection?>(null) }
    var clipState by remember(cam.id) { mutableStateOf(ClipExportState.Idle) }
    var clipError by remember(cam.id) { mutableStateOf<String?>(null) }
    var clipUri by remember(cam.id) { mutableStateOf<Uri?>(null) }
    // Live `System.currentTimeMillis()`, deliberately NOT the pinned `nowAnchor` the timeline lays
    // itself out with: a player left open for hours would otherwise keep offering a start time
    // Frigate has since rotated out of retention.
    val clipBounds = exportBounds(TimeWindow(startMs, endMs), System.currentTimeMillis())
    val prev = events.lastOrNull { it.startMs < headTime }
    val next = events.firstOrNull { it.startMs > headTime }
    // The clip under the playhead (containment, not nearest): scrubbing can rest anywhere, and
    // gaps honestly show "no saved recording".
    val selected = if (isLive || !isRing) {
        null
    } else {
        clipContaining(events, headTime, loadedClip?.first, loadedClip?.second)
    }
    // The event whose AI description the strip shows. Separate from `selected`
    // above, which is deliberately Ring-only (it drives per-clip stream
    // resolution); descriptions are a Frigate feature, so this one is
    // backend-agnostic and needs no loaded-clip refinement — Frigate events carry
    // real end times.
    val describedEvent = clipContaining(events, headTime, null, null)

    fun seek(ms: Long) {
        scrubbing = false
        playhead = ms.coerceIn(startMs, endMs)
        paused = false
    }

    fun goLive() {
        scrubbing = false
        playhead = null
    }

    // ring recorded playback: select the event, then take the recording URL ring-mqtt publishes for
    // it (see resolveRingClip). Tri-state per clip — Resolving / Ready / Failed — so a recording
    // Ring can't produce (timeout, rotated-out event, no Protect subscription) surfaces as an honest
    // error with a Retry, never a permanent "Loading…". Kept in remember(cam.id) state (not
    // produceState's value) so a camera switch resets it — ring-mqtt option ids ("Motion 1"…) repeat
    // across cameras.
    var ringClip by remember(cam.id) { mutableStateOf<RingClipState>(RingClipState.Idle) }
    LaunchedEffect(isLive, selected?.id, retryNonce, timeline) {
        val sel = selected
        // Skipped entirely when the ring-timeline service answered: its events already carry a
        // playable URL, so there is nothing to select, wait for, or fail at.
        if (!isRing || isLive || sel == null || cam.eventSelectId == null || timeline != null) {
            return@LaunchedEffect
        }
        // Already resolving/ready for this clip (e.g. scrub within its span, or a scrub that left
        // and re-entered it) — don't re-fire select_option/stream.
        val cur = ringClip
        val busyFor = when (cur) {
            is RingClipState.Resolving -> cur.clipId
            is RingClipState.Ready -> cur.clipId
            else -> null
        }
        if (busyFor == sel.id) return@LaunchedEffect
        if (scrubbing) delay(SCRUB_CLIP_DEBOUNCE_MS)
        try {
            ringClip = RingClipState.Resolving(sel.id)
            if (loadedClip?.first != sel.id) loadedClip = null
            ringClip = viewModel.resolveRingClip(cam.eventSelectId, sel.id, cam.eventStreamId)
        } finally {
            // A resolution cancelled mid-flight (scrubbed away / relaunched) must not leave the
            // state looking like it's still loading — reset so a return to this clip re-resolves.
            (ringClip as? RingClipState.Resolving)?.let {
                if (it.clipId == sel.id) ringClip = RingClipState.Idle
            }
        }
    }

    // Continuous (Frigate) VOD spans the WHOLE window and is built once — scrubbing seeks within it
    // (see seekToMs below) instead of rebuilding a playlist per move, which re-buffered (stutter)
    // and could crash ExoPlayer on a backwards seek. A ring clip seeks from ITS start (so scrubbing
    // inside a clip previews live).
    val ringReady = (ringClip as? RingClipState.Ready)?.takeIf { it.clipId == selected?.id }
    // On the ring-timeline service path, ONE pure function decides which recorded source plays: the
    // 24/7 continuous track when it covers this moment, else the event clip (both arrive with their
    // URLs already signed, so neither has a round trip or a loading state). The ring-mqtt selector
    // path has neither a urls map nor footage, so it keeps its own per-clip resolution above.
    val source = if (isLive || !isRing || timeline == null) {
        null
    } else {
        chooseRecordedSource(
            headMs = headTime,
            segments = footage?.segments.orEmpty(),
            events = events,
            urls = timeline.urls,
            loadedClipId = loadedClip?.first,
            loadedDurationMs = loadedClip?.second,
        )
    }
    val playable: Pair<String, Long>? = when (source) {
        is RecordedSource.Footage -> source.url to source.seekToMs
        is RecordedSource.Clip -> source.url to source.seekToMs
        else -> null
    }
    // Keyed by the *source* being played, not the event: on the continuous track the user can sit
    // on one stitched segment for far longer than a signature lives, and that segment has no event
    // id to key on.
    val sourceKey = (source as? RecordedSource.Footage)?.let { "footage:${it.segment.startMs}" }
        ?: selected?.id
    // The VOD is a bounded PAGE around the playhead, not the whole window. A Frigate manifest is
    // capped at ~1024 segments by nginx-vod-module (~3h at these segment lengths) and 503s past
    // it, so a window spanning days — or even the old 24h — cannot be one manifest. Pages are
    // grid-aligned, so scrubbing within a page keeps the same URL and the player does not reload.
    val vodPage = remember(isLive, isRing, headTime, startMs, endMs) {
        if (isLive || isRing) null else vodPageFor(headTime, TimeRange(startMs, endMs))
    }
    // Frigate VOD must also be SIGNED or every segment 401s and the video is silently black — see
    // Source.signedRecordingUrlAt. Signing is a websocket round trip, so it resolves in an effect
    // rather than during composition. Keyed on the PAGE: a signature only authorises the path
    // prefix it was minted for, so it re-signs exactly when the page turns.
    val signedVodUrl by produceState<String?>(null, cameraName, vodPage) {
        // produceState does NOT reset on key change — it keeps the previous page's URL while the
        // new signature resolves. Left in place, the player briefly plays the OLD page with a seek
        // computed against the NEW page's origin (a plausible-but-wrong moment), and the URL then
        // swaps under a mounted player. Reset first: the placeholder covers the round trip, and
        // the player remounts cleanly when the signed URL lands.
        value = null
        val page = vodPage
        if (page != null) value = viewModel.signedRecordingUrl(cameraName, page.startMs, page.endMs)
    }
    val recordingUrl = when {
        isLive -> null
        isRing -> playable?.first ?: ringReady?.url
        // Null until signing returns; the player simply has nothing to load for that moment,
        // which is the same state it is in while a Ring clip URL resolves.
        else -> signedVodUrl
    }
    val seekToMs = when {
        isLive -> null
        isRing -> playable?.second
            ?: if (ringReady != null && selected != null) offsetInClipMs(selected, headTime) else null
        // Offset within the PAGE, not the window — the VOD's zero is the page start. Using
        // startMs here would seek to a plausible but wrong moment, which is worse than an obvious
        // failure because it looks like the recording itself is wrong.
        else -> vodPositionMsInPage(headTime, vodPage?.startMs ?: startMs)
    }

    // Give the timeline the loaded clip's real span so chip width agrees with containment.
    val displayEvents = loadedClip?.let { (id, dur) ->
        events.map { e ->
            if (e.id == id && e.endMs == null) e.copy(endMs = clipSpanEndMs(e, id, dur)) else e
        }
    } ?: events

    // In fullscreen the picture IS the screen: the chrome, timeline, description and transport
    // are all hidden, so the system back gesture has to be the way out or the user is stuck with
    // only the small overlay button. Registered before the layout so it wins over navigation.
    BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ONE FlowRow that wraps only when it has to.
        //
        // A plain Row cannot hold this many controls on a phone: Row gives every
        // child its minimum width and then keeps going, so the overflow doesn't
        // clip or scroll — the last chips get squeezed until their labels wrap one
        // character per line ("S n a p s h o t" stacked vertically). But forcing a
        // second row unconditionally is also wrong: it costs a row of height on
        // every camera, which pushed the transport bar off-screen. FlowRow gives
        // one line when the set fits and extra lines only when it doesn't — and
        // the set genuinely varies (Move only for PTZ, Low/High only with a sub
        // stream, Talk only for Ring, Siren only where one exists).
        if (!fullscreen) FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraSwitcher(cameras = cameras, current = cam, onSelect = onSelectCamera)
            if (isLive && ptz != null) {
                MoveButton(active = showPtz, onToggle = { showPtz = !showPtz })
            }
            if (isLive && subAvailable) {
                QualityToggle(low = qualityLow, onChange = { qualityLow = it })
            }
            MuteButton(muted = muted, onToggle = { muted = !muted })
            FullscreenButton(active = fullscreen, onToggle = { fullscreen = !fullscreen })
            SnapshotButton(snapshotUrl = cam.snapshotUrl, cameraName = cameraName)
            // The one and only gate. Frigate is the only backend that can cut an arbitrary range:
            // Ring exposes whole pre-signed event clips that expire in ~15 min and cannot be
            // trimmed, so offering a range selector there would promise something it can't do.
            // Hidden while live too — there is nothing to export from the future.
            if (isFrigate && !isLive) {
                ClipButton(
                    active = clipSel != null,
                    onToggle = {
                        clipSel =
                            if (clipSel != null) null else defaultSelection(headTime, clipBounds)
                        clipError = null
                        clipState = ClipExportState.Idle
                    },
                )
            }
            // LIVE ONLY, and the gate is load-bearing rather than cosmetic: TalkButton latches the
            // mic open until tapped again, and unmounting it is what closes the session. Offering
            // it over recorded footage would be both meaningless (there is nothing to talk to) and
            // the one way a mic could stay open with no live camera on screen.
            //
            // The other half was `isRing` until 2026-08-05, which is a fact about where a camera's
            // RECORDINGS live and says nothing about whether it has a speaker. It excluded all
            // seven Reolinks from a feature they support. Now the same question the Reply button
            // asks, through the same predicate, so the two can never disagree about one camera.
            if (canReachSpeaker(canGo2rtc) && isLive) {
                TalkButton(cameraName, viewModel)
            }
            // Prerecorded messages, played out of the camera's own speaker by go2rtc. A chip like
            // every other control here, deliberately: the 2026-08-03 attempt to give it a larger
            // treatment of its own is exactly what made the row look mismatched.
            //
            // Gated on go2rtc serving this camera, because go2rtc is what carries the backchannel
            // to the speaker, and it fails CLOSED — no button while the stream list is in flight,
            // and none at all on a camera that cannot talk. Live-only for the same reason Talk is:
            // there is nothing to say to a recording.
            if (canReachSpeaker(canGo2rtc) && isLive) {
                ReplyButton(onClick = { showReplies = true })
            }
            cam.sirenSwitchId?.let { sirenId -> SirenButton(sirenId, viewModel) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isLive) HawksnestTheme.pulse.recovery
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
                Text(
                    if (isLive) "Live" else "Recorded",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // Transport ladder: recorded VOD (when scrubbed) → live RTSP straight to the camera →
        // live WebRTC (go2rtc) → live WebRTC (HA) → live HLS/demo video → MJPEG proxy → snapshot.
        // Everything above HLS is continuous; HLS is segmented, which is what "jumpy" live video
        // actually is — so a camera with a better tier available should never reach it.
        // Web's ladder is the same minus the RTSP tier, which browsers cannot play at all.
        //
        // ZoomableFrame wraps the WHOLE ladder rather than any one tier: all seven already share
        // this one `frame` modifier, so pinch-zoom applies to every one of them identically and
        // cannot drift. Fullscreen swaps the fixed 16:9 box for the whole screen — the `when`
        // stays in the same composition slot either way, so the player is NOT torn down and
        // re-created (which on the WebRTC tiers is a 2-4s renegotiation).
        ZoomableFrame(
            zoom = zoom,
            onZoomChange = { zoom = it },
            modifier = if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
        ) { zoomed ->
        val frame = zoomed.fillMaxSize()
        when {
            !isLive && recordingUrl != null ->
                VideoPlayer(
                    recordingUrl,
                    frame,
                    paused = paused,
                    muted = muted,
                    seekToMs = seekToMs,
                    // Learn the loaded ring clip's real duration from the media; an ExoPlayer
                    // failure after the URL resolved is a (retryable) failure too.
                    onDurationMs = if (isRing) {
                        { dur ->
                            (ringClip as? RingClipState.Ready)?.let { rc ->
                                if (loadedClip?.first != rc.clipId || loadedClip?.second != dur) {
                                    loadedClip = rc.clipId to dur
                                }
                            }
                        }
                    } else {
                        null
                    },
                    onError = if (isRing) {
                        {
                            // On the ring-timeline path the overwhelmingly likely cause is Ring's
                            // ~15-minute signature having expired, so refetch (fresh URLs) once per
                            // source before calling it a failure — the guard stops a genuinely dead
                            // clip from looping.
                            if (timeline != null && sourceKey != null && refetchedFor != sourceKey) {
                                refetchedFor = sourceKey
                                timelineNonce += 1
                            } else {
                                (ringClip as? RingClipState.Ready)?.let { rc ->
                                    ringClip = RingClipState.Failed(rc.clipId)
                                }
                            }
                        }
                    } else {
                        null
                    },
                    // Frigate VOD only: its nested manifest needs a Bearer token that a URL
                    // signature cannot cover. Ring's URLs are pre-signed by Ring and must NOT
                    // carry an HA token, so this stays null there.
                    authToken = if (isRing) null else mediaAuthToken,
                )
            // Scrubbed to a past moment with no footage on screen: a clip is resolving, resolution
            // failed (Retry), or no recording is kept for this time — show the snapshot with an
            // honest note rather than snapping the frame back to the live feed.
            !isLive -> ScrubbedPlaceholder(
                snapshotUrl = cam.snapshotUrl,
                state = when {
                    // Footage WAS recorded here, this player just has no key for it — neither a
                    // failure to retry nor "nothing recorded". Its own message.
                    source is RecordedSource.Encrypted -> PlaceholderState.Encrypted
                    // On the ring-timeline path there is no resolving step: an event on the
                    // timeline arrived with its URL, so no source for it is a failure, not a spinner.
                    selected != null && timeline != null -> PlaceholderState.Failed
                    selected != null && (ringClip as? RingClipState.Failed)?.clipId == selected.id ->
                        PlaceholderState.Failed
                    selected != null -> PlaceholderState.Resolving
                    else -> PlaceholderState.None
                },
                // Retrying means fresh signed URLs on the service path, re-resolution on the other.
                onRetry = {
                    refetchedFor = null
                    if (timeline != null) timelineNonce += 1 else retryNonce += 1
                },
                modifier = frame,
            )
            // Low quality bypasses RTSP-direct (it plays the main stream) — see `useSub`.
            isLive && canRtsp && !useSub -> RtspPlayer(
                url = rtspUrl!!,
                camera = cameraName,
                onFail = { rtspFailed = true },
                muted = muted,
                modifier = frame,
            )
            isLive && (canGo2rtc == true || useSub) && !go2rtcFailed -> Go2rtcPlayer(
                src = go2rtcSrc,
                cameraId = cam.id,
                baseUrl = viewModel.baseUrl(),
                onFail = { go2rtcFailed = true },
                muted = muted,
                modifier = frame,
            )
            // `canGo2rtc != null` holds this arm while the stream list is in flight. Starting an HA
            // WebRTC negotiation only to tear it down when go2rtc turns out to be available wastes
            // the PeerConnection; the snapshot arm below renders for the (cached, sub-second) wait.
            isLive && canGo2rtc != null && canWebRtc && !webRtcFailed -> WebRtcPlayer(
                entityId = cam.entityId,
                cameraId = cam.id,
                viewModel = viewModel,
                onFail = { webRtcFailed = true },
                muted = muted,
                modifier = frame,
            )
            // live = true pins the HLS feed near the live edge (no fast-forward catch-up). loop
            // stays true so the demo clip — DEMO_CLIP_URI, which VideoPlayer excludes from live
            // handling — keeps looping as a fake-live feed.
            liveUrl != null -> VideoPlayer(liveUrl!!, frame, loop = true, live = true, muted = muted)
            cam.streamUrl != null -> MjpegView(
                streamUrl = cam.streamUrl!!,
                snapshotUrl = cam.snapshotUrl,
                modifier = frame,
            )
            else -> RefreshingSnapshot(url = cam.snapshotUrl, modifier = frame)
        }

            // The only on-screen way out of fullscreen (back also works). Inside the frame so it
            // sits over the picture, and NOT inside the graphicsLayer content — it must stay put
            // and stay the same size while the picture underneath is magnified and panned.
            // Fullscreen hides the control row above the picture, so the essentials come back
            // as chrome on the frame. Previously this was an exit button alone, which meant
            // losing Mute, Reply and the way to hear anything exactly when the picture was
            // biggest.
            if (fullscreen) {
                FullscreenChrome(
                    muted = muted,
                    onToggleMute = { muted = !muted },
                    onExitFullscreen = { fullscreen = false },
                    onReply = { showReplies = true }
                        .takeIf { canReachSpeaker(canGo2rtc) && isLive },
                )
            }
        }

        if (!fullscreen) {
            // Live only: moving the lens while watching recorded footage would re-aim
            // the camera with no visible feedback. Leaving composition is also what
            // guarantees an in-flight move is stopped (see PtzPad).
            if (showReplies) {
                ReplySheet(
                    cameraName = cameraName,
                    displayName = cam.name,
                    viewModel = viewModel,
                    onDismiss = { showReplies = false },
                )
            }

            ptz?.takeIf { isLive && showPtz }?.let { PtzPanel(it, viewModel) }

            Timeline24h(
                events = displayEvents,
                startMs = startMs,
                endMs = endMs,
                playhead = playhead,
                onSeek = ::seek,
                footage = footageLane,
                onScrub = { ms ->
                    scrubbing = true
                    playhead = ms.coerceIn(startMs, endMs)
                },
                onLive = ::goLive,
                selection = clipSel,
                selectionBounds = clipBounds,
                onSelectionChange = { next ->
                    clipSel = next
                    clipError = null
                    clipState = ClipExportState.Idle
                },
            )

            // Between the timeline and the transport on purpose: tapping a chip
            // already seeks, so the description follows the playhead with no new
            // interaction to learn.
            EventDescription(describedEvent)

            // Replaces the transport rather than stacking under it: the column is already tight
            // at phone width (pushing the transport off-screen has happened before), and
            // prev/next/play are not what you reach for while marking a range.
            val sel = clipSel
            if (sel != null) {
                ClipExportBar(
                    selection = sel,
                    playheadMs = headTime,
                    footage = footageLane,
                    state = clipState,
                    error = clipError,
                    onNudge = { edge, delta ->
                        clipSel = nudgeClip(sel, edge, delta, clipBounds)
                        clipError = null
                        clipState = ClipExportState.Idle
                    },
                    onSetEdgeToPlayhead = { edge ->
                        clipSel = setClipEdge(sel, edge, headTime, clipBounds)
                        clipError = null
                        clipState = ClipExportState.Idle
                    },
                    onCancel = {
                        clipSel = null
                        clipError = null
                        clipState = ClipExportState.Idle
                    },
                    onShare = clipUri?.let { uri -> { shareClip(context, uri) } },
                    onDownload = {
                        clipState = ClipExportState.Preparing
                        clipError = null
                        clipUri = null
                        viewModel.exportClip(context, frigateExportName, cameraName, sel) { result ->
                            when (result) {
                                is ClipSaveResult.Saved -> {
                                    clipUri = result.uri
                                    clipState = ClipExportState.Started
                                }
                                is ClipSaveResult.Failed -> {
                                    clipError = result.message
                                    clipState = ClipExportState.Failed
                                }
                            }
                        }
                    },
                )
            } else {
                TransportBar(
                    isLive = isLive,
                    isPaused = paused,
                    canPrev = prev != null,
                    canNext = next != null || !isLive,
                    onPrev = { prev?.let { seek(it.startMs) } },
                    onNext = { if (next != null) seek(next.startMs) else goLive() },
                    onTogglePlay = { paused = !paused },
                    onLive = ::goLive,
                )
            }
        }
    }
}

/** What the scrubbed-placeholder frame should say (mirrors the web's placeholder states). */
private enum class PlaceholderState { Resolving, Failed, Encrypted, None }

/**
 * The frame shown when the timeline is scrubbed to a moment with no footage on screen — the
 * camera's snapshot, dimmed, with an honest note. [PlaceholderState.Resolving] means a playable
 * clip's stream is still being produced; [PlaceholderState.Failed] means HA couldn't produce it
 * (timeout / error / the event rotated out of ring-mqtt's selector) and offers a Retry;
 * [PlaceholderState.None] means no recording is kept for this time.
 */
@Composable
private fun ScrubbedPlaceholder(
    snapshotUrl: String?,
    state: PlaceholderState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CameraSnapshot(model = snapshotUrl, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (state) {
                    PlaceholderState.Resolving -> "Loading recording…"
                    PlaceholderState.Failed -> "Couldn't load this recording"
                    PlaceholderState.Encrypted -> "This footage is end-to-end encrypted"
                    PlaceholderState.None -> "No saved recording for this moment"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state == PlaceholderState.Failed) HawksnestTheme.pulse.streak else Color.White,
            )
            if (state == PlaceholderState.Failed) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "Retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Manual siren toggle bound to ring-mqtt's `switch.<base>_siren` (rendered only when that
 * entity exists — siren-capable cameras). The siren is loud, so turning it ON is a two-tap
 * action (first tap arms "Confirm", a second within ~3s fires it); turning it OFF is one tap.
 */
@Composable
private fun SirenButton(
    entityId: String,
    viewModel: CameraPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val on by viewModel.sirenOn(entityId).collectAsState(initial = false)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    val pulse = HawksnestTheme.pulse
    val bg = when {
        on -> pulse.streak
        armed -> pulse.streakDim
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        on -> Color.White
        armed -> pulse.streak
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable {
                when {
                    on -> {
                        viewModel.setSiren(entityId, false)
                        armed = false
                    }
                    !armed -> armed = true
                    else -> {
                        viewModel.setSiren(entityId, true)
                        armed = false
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Campaign, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            if (on) "Siren on" else if (armed) "Confirm" else "Siren",
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}
