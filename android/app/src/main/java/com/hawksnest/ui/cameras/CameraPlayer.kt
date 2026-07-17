package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.hawksnest.core.logic.clipContaining
import com.hawksnest.core.logic.clipSpanEndMs
import com.hawksnest.core.logic.offsetInClipMs
import com.hawksnest.core.logic.vodPositionMs
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
    modifier: Modifier = Modifier,
) {
    val cameraName = cameraNameOf(cam.id)
    val isRing = cam.eventSelectId != null
    // Fix the timeline window when the player opens (rolling 24h ending now).
    val window = remember { System.currentTimeMillis().let { it - DAY_MS to it } }
    val (startMs, endMs) = window

    // Only real recordings make the timeline (Ring-style: every block is watchable) — ring's ~5
    // selector events at their real times, or Frigate/demo events that carry a clip.
    val events: List<CameraEvent> by produceState<List<CameraEvent>>(emptyList(), cam.id) {
        value = runCatching {
            if (isRing) {
                viewModel.ringEvents(cam.eventSelectId!!, cameraName, startMs, endMs)
            } else {
                viewModel.events(cameraName, startMs, endMs).filter { it.hasClip }
            }
        }.getOrDefault(emptyList())
    }
    // null playhead = live; reset to live whenever the camera changes.
    var playhead by remember(cam.id) { mutableStateOf<Long?>(null) }
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
    // Ring cameras get an even faster tier ABOVE HA WebRTC: WebRTC straight to the dedicated
    // go2rtc (native `ring:` source, ~1-2s). Snapshot the circuit-breaker at mount (mirrors
    // canWebRtc); it's skipped once media is known-unreachable (before the §7c :8555 forwarder).
    val canGo2rtc = remember(cam.id) { isRing && Go2rtcHealth.maybeAvailable() }
    var go2rtcFailed by remember(cam.id) { mutableStateOf(false) }

    // Resolve the HLS stream URL only once the HLS tier could actually render — NOT eagerly on
    // open. `camera/stream` makes HA spin up a stream pipeline, which on a battery camera wakes
    // it / competes for its single live session in parallel with the WebRTC negotiation above it
    // on the ladder (the request itself is bounded at 15s in HaSource). Both live-WebRTC tiers
    // (go2rtc-direct, then HA) must be exhausted before we resolve HLS.
    val wantsHls = !(canGo2rtc && !go2rtcFailed) && !(canWebRtc && !webRtcFailed)
    val liveUrl: String? by produceState<String?>(null, cam.id, wantsHls) {
        value = if (wantsHls) viewModel.liveStreamUrl(cam.entityId) else null
    }

    val isLive = playhead == null
    val headTime = playhead ?: endMs
    val prev = events.lastOrNull { it.startMs < headTime }
    val next = events.firstOrNull { it.startMs > headTime }
    // The clip under the playhead (containment, not nearest): scrubbing can rest anywhere, and
    // gaps honestly show "no saved recording".
    val selected = if (isLive || !isRing) {
        null
    } else {
        clipContaining(events, headTime, loadedClip?.first, loadedClip?.second)
    }

    fun seek(ms: Long) {
        scrubbing = false
        playhead = ms.coerceIn(startMs, endMs)
        paused = false
    }

    fun goLive() {
        scrubbing = false
        playhead = null
    }

    // ring recorded playback: select the event, then stream the `_event` camera. Tri-state per clip
    // — Resolving / Ready / Failed — so a stream HA can't produce (15s timeout, sleeping battery
    // cam, rotated-out event) surfaces as an honest error with a Retry, never a permanent
    // "Loading…". Kept in remember(cam.id) state (not produceState's value) so a camera switch
    // resets it — ring-mqtt option ids ("Motion 1"…) repeat across cameras.
    var ringClip by remember(cam.id) { mutableStateOf<RingClipState>(RingClipState.Idle) }
    LaunchedEffect(isLive, selected?.id, retryNonce) {
        val sel = selected
        if (!isRing || isLive || sel == null || cam.eventSelectId == null || cam.eventStreamId == null) {
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
    val recordingUrl = when {
        isLive -> null
        isRing -> ringReady?.url
        else -> viewModel.recordingUrl(cameraName, startMs, endMs)
    }
    val seekToMs = when {
        isLive -> null
        isRing -> if (ringReady != null && selected != null) offsetInClipMs(selected, headTime) else null
        else -> vodPositionMs(headTime, startMs)
    }

    // Give the timeline the loaded clip's real span so chip width agrees with containment.
    val displayEvents = loadedClip?.let { (id, dur) ->
        events.map { e ->
            if (e.id == id && e.endMs == null) e.copy(endMs = clipSpanEndMs(e, id, dur)) else e
        }
    } ?: events

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraSwitcher(cameras = cameras, current = cam, onSelect = onSelectCamera)
            Spacer(Modifier.weight(1f))
            if (isRing && isLive) {
                TalkButton(cameraName, viewModel)
                Spacer(Modifier.size(8.dp))
            }
            cam.sirenSwitchId?.let { sirenId ->
                SirenButton(sirenId, viewModel)
                Spacer(Modifier.size(8.dp))
            }
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
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        // Transport ladder: recorded VOD (when scrubbed) → live WebRTC (go2rtc) → live HLS/demo
        // video → MJPEG proxy → snapshot. Mirrors the web LivePlayer's step-down.
        val frame = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
        when {
            !isLive && recordingUrl != null ->
                VideoPlayer(
                    recordingUrl,
                    frame,
                    paused = paused,
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
                            (ringClip as? RingClipState.Ready)?.let { rc ->
                                ringClip = RingClipState.Failed(rc.clipId)
                            }
                        }
                    } else {
                        null
                    },
                )
            // Scrubbed to a past moment with no footage on screen: a clip is resolving, resolution
            // failed (Retry), or no recording is kept for this time — show the snapshot with an
            // honest note rather than snapping the frame back to the live feed.
            !isLive -> ScrubbedPlaceholder(
                snapshotUrl = cam.snapshotUrl,
                state = when {
                    selected != null && (ringClip as? RingClipState.Failed)?.clipId == selected.id ->
                        PlaceholderState.Failed
                    selected != null -> PlaceholderState.Resolving
                    else -> PlaceholderState.None
                },
                onRetry = { retryNonce += 1 },
                modifier = frame,
            )
            isLive && canGo2rtc && !go2rtcFailed -> Go2rtcPlayer(
                src = cameraName,
                cameraId = cam.id,
                baseUrl = viewModel.baseUrl(),
                onFail = { go2rtcFailed = true },
                modifier = frame,
            )
            isLive && canWebRtc && !webRtcFailed -> WebRtcPlayer(
                entityId = cam.entityId,
                cameraId = cam.id,
                viewModel = viewModel,
                onFail = { webRtcFailed = true },
                modifier = frame,
            )
            // live = true pins the HLS feed near the live edge (no fast-forward catch-up). loop
            // stays true so the demo clip — DEMO_CLIP_URI, which VideoPlayer excludes from live
            // handling — keeps looping as a fake-live feed.
            liveUrl != null -> VideoPlayer(liveUrl!!, frame, loop = true, live = true)
            cam.streamUrl != null -> MjpegView(
                streamUrl = cam.streamUrl!!,
                snapshotUrl = cam.snapshotUrl,
                modifier = frame,
            )
            else -> RefreshingSnapshot(url = cam.snapshotUrl, modifier = frame)
        }

        Timeline24h(
            events = displayEvents,
            startMs = startMs,
            endMs = endMs,
            playhead = playhead,
            onSeek = ::seek,
            onScrub = { ms ->
                scrubbing = true
                playhead = ms.coerceIn(startMs, endMs)
            },
            onLive = ::goLive,
        )

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

/** What the scrubbed-placeholder frame should say (mirrors the web's placeholder states). */
private enum class PlaceholderState { Resolving, Failed, None }

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
