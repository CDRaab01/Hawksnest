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
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.ui.home.CameraUi
import com.hawksnest.ui.theme.HawksnestTheme

private const val DAY_MS = 24 * 3600_000L

/** The Frigate camera name backing a `camera.<slug>` entity. */
private fun cameraNameOf(entityId: String): String = entityId.substringAfter('.', entityId)

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
    val cameraName = cameraNameOf(cam.entityId)
    // Fix the timeline window when the player opens (rolling 24h ending now).
    val window = remember { System.currentTimeMillis().let { it - DAY_MS to it } }
    val (startMs, endMs) = window

    val events: List<CameraEvent> by produceState<List<CameraEvent>>(emptyList(), cam.entityId) {
        value = viewModel.events(cameraName, startMs, endMs)
    }
    val liveUrl: String? by produceState<String?>(null, cam.entityId) {
        value = viewModel.liveStreamUrl(cam.entityId)
    }

    // null playhead = live; reset to live whenever the camera changes.
    var playhead by remember(cam.entityId) { mutableStateOf<Long?>(null) }
    var paused by remember(cam.entityId) { mutableStateOf(false) }

    val isLive = playhead == null
    val headTime = playhead ?: endMs
    val prev = events.lastOrNull { it.startMs < headTime }
    val next = events.firstOrNull { it.startMs > headTime }

    fun seek(ms: Long) {
        playhead = ms.coerceIn(startMs, endMs)
        paused = false
    }

    val recordingUrl = if (isLive) null else viewModel.recordingUrl(cameraName, headTime, endMs)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraSwitcher(cameras = cameras, current = cam, onSelect = onSelectCamera)
            Spacer(Modifier.weight(1f))
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

        // Transport ladder: recorded VOD (when scrubbed) → live HLS/demo video → MJPEG proxy →
        // snapshot. Mirrors the web LivePlayer's step-down.
        val frame = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
        when {
            !isLive && recordingUrl != null -> VideoPlayer(recordingUrl, frame, paused = paused)
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
