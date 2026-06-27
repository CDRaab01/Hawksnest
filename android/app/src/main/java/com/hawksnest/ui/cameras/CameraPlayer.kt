package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.ringEventsFromSelect
import com.hawksnest.ui.home.CameraUi
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DAY_MS = 24 * 3600_000L

/** The Frigate/ring camera name backing a `camera.<slug>` logical id. */
private fun cameraNameOf(id: String): String = id.substringAfter('.', id)

/**
 * Ring-style camera player: live feed + a scrubbable 24h timeline of recorded events, an in-player
 * camera switcher, and transport controls. The playhead is null (live) or an epoch-ms time
 * (recorded HLS VOD). Events + recordings come from the active source — Frigate live, or synthesized
 * demo data playing the bundled clip. Mirrors the web `CameraPlayer`.
 */
@Composable
fun CameraPlayer(
    cam: CameraUi,
    cameras: List<CameraUi>,
    onSelectCamera: (CameraUi) -> Unit,
    viewModel: CameraPlayerViewModel,
    bucket: Long,
    modifier: Modifier = Modifier,
) {
    val cameraName = cameraNameOf(cam.id)
    val isRing = cam.eventSelectId != null
    // Fix the timeline window when the player opens (rolling 24h ending now).
    val window = remember { System.currentTimeMillis().let { it - DAY_MS to it } }
    val (startMs, endMs) = window

    // Demo/Frigate events come from the source; ring events come off the selector.
    val events: List<CameraEvent> by produceState<List<CameraEvent>>(emptyList(), cam.id) {
        value = if (isRing) {
            ringEventsFromSelect(viewModel.entity(cam.eventSelectId!!), cameraName, endMs)
        } else {
            viewModel.events(cameraName, startMs, endMs)
        }
    }
    val liveUrl: String? by produceState<String?>(null, cam.id) {
        value = viewModel.liveStreamUrl(cam.entityId)
    }

    // null playhead = live; reset to live whenever the camera changes.
    var playhead by remember(cam.id) { mutableStateOf<Long?>(null) }
    var paused by remember(cam.id) { mutableStateOf(false) }
    // Ring/go2rtc live is WebRTC (sub-second). Try it first; on failure, step down to HLS/MJPEG.
    val canWebRtc = viewModel.canWebRtc(cam.entityId)
    var webRtcFailed by remember(cam.id) { mutableStateOf(false) }

    val isLive = playhead == null
    val headTime = playhead ?: endMs
    val prev = events.lastOrNull { it.startMs < headTime }
    val next = events.firstOrNull { it.startMs > headTime }
    val selected = if (isLive) null else events.firstOrNull { it.startMs == headTime }

    fun seek(ms: Long) {
        playhead = if (isRing && events.isNotEmpty()) {
            // No continuous VOD on ring — snap to the nearest recorded event.
            events.minByOrNull { abs(it.startMs - ms) }!!.startMs
        } else {
            ms.coerceIn(startMs, endMs)
        }
        paused = false
    }

    // ring recorded playback: select the event, then stream the `_event` camera.
    val ringSrc: String? by produceState<String?>(null, isLive, selected?.id) {
        value = if (isRing && !isLive && selected != null &&
            cam.eventSelectId != null && cam.eventStreamId != null
        ) {
            viewModel.playRingEvent(cam.eventSelectId, selected.id, cam.eventStreamId)
        } else {
            null
        }
    }

    val recordingUrl = when {
        isLive -> null
        isRing -> ringSrc
        else -> viewModel.recordingUrl(cameraName, headTime, endMs)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraSwitcher(cameras = cameras, current = cam, onSelect = onSelectCamera)
            Spacer(Modifier.weight(1f))
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
            !isLive && recordingUrl != null -> VideoPlayer(recordingUrl, frame, paused = paused)
            isLive && canWebRtc && !webRtcFailed -> WebRtcPlayer(
                entityId = cam.entityId,
                viewModel = viewModel,
                onFail = { webRtcFailed = true },
                modifier = frame,
            )
            liveUrl != null -> VideoPlayer(liveUrl!!, frame, loop = true)
            cam.streamUrl != null -> MjpegView(
                streamUrl = cam.streamUrl!!,
                snapshotUrl = cam.snapshotUrl,
                bucket = bucket,
                modifier = frame,
            )
            else -> CameraSnapshot(model = bustCache(cam.snapshotUrl, bucket), modifier = frame)
        }

        Timeline24h(
            events = events,
            startMs = startMs,
            endMs = endMs,
            playhead = playhead,
            onSeek = ::seek,
        )

        TransportBar(
            isLive = isLive,
            isPaused = paused,
            canPrev = prev != null,
            canNext = next != null || !isLive,
            onPrev = { prev?.let { seek(it.startMs) } },
            onNext = { if (next != null) seek(next.startMs) else playhead = null },
            onTogglePlay = { paused = !paused },
            onLive = { playhead = null },
        )
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
    val scope = rememberCoroutineScope()
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
                        scope.launch { viewModel.setSiren(entityId, false) }
                        armed = false
                    }
                    !armed -> armed = true
                    else -> {
                        scope.launch { viewModel.setSiren(entityId, true) }
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
