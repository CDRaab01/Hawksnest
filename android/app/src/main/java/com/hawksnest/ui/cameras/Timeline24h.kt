package com.hawksnest.ui.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawksnest.core.logic.HOUR_MS
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.TimeWindow
import com.hawksnest.core.logic.Viewport
import com.hawksnest.core.logic.pan
import com.hawksnest.core.logic.ticks
import com.hawksnest.core.logic.timeToX
import com.hawksnest.core.logic.viewportForSpan
import com.hawksnest.core.logic.visibleSpanMs
import com.hawksnest.core.logic.xToTime
import com.hawksnest.core.logic.zoom
import com.hawksnest.ui.theme.HawksnestTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun clockTime(ms: Long): String =
    TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Opening zoom: ~3h visible, clamped into the [10min, 24h] range. */
private const val DEFAULT_SPAN_MS = 3 * HOUR_MS
private const val TAP_SLOP_PX = 8f

/** Compact span label for the zoom indicator ("45m", "3h", "1h 30m"). */
private fun formatSpan(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(1L)
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}

/**
 * Ring-style scrubbable timeline: a center-anchored, zoomable + pannable strip drawn on a Canvas.
 * Drag left/right to move through time; pinch to zoom (≈10 min → 24 h). The playhead marks the
 * current time — pinned at center while scrubbing, at the right edge while live. A clean tap seeks
 * to the tapped time (CameraPlayer's seek() snaps ring to the nearest event). All the mapping/clamp
 * math lives in `core/logic/TimelineViewport`. Mirrors the web `Timeline24h`.
 */
@Composable
fun Timeline24h(
    events: List<CameraEvent>,
    startMs: Long,
    endMs: Long,
    playhead: Long?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val measurer = rememberTextMeasurer()
    val window = TimeWindow(startMs, endMs)
    val scrubTime = playhead ?: endMs

    var trackWidth by remember { mutableStateOf(0f) }
    var vp by remember { mutableStateOf<Viewport?>(null) }

    fun colorFor(label: String): Color = when (label) {
        "person" -> pulse.strength
        "car", "truck" -> pulse.effort
        "dog", "cat" -> pulse.recovery
        else -> pulse.streak
    }

    // Re-center on external seeks (Live / prev / next) and width changes, preserving zoom.
    LaunchedEffect(playhead, trackWidth, startMs, endMs) {
        if (trackWidth > 0f) {
            val span = vp?.let { visibleSpanMs(it, trackWidth).toLong() } ?: DEFAULT_SPAN_MS
            vp = viewportForSpan(scrubTime, span, trackWidth, window)
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(
                if (playhead == null) "Live" else clockTime(scrubTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${events.size} events",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .pointerInput(startMs, endMs, trackWidth) {
                    if (trackWidth <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            vp?.let { cur ->
                                var nv = cur
                                if (zoomChange != 1f) {
                                    nv = zoom(nv, zoomChange, trackWidth, window)
                                    moved = true
                                }
                                if (panChange.x != 0f) {
                                    nv = pan(nv, panChange.x, trackWidth, window)
                                    totalDx += panChange.x
                                    if (abs(totalDx) > TAP_SLOP_PX) moved = true
                                }
                                if (nv != cur) vp = nv
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        val v = vp
                        if (v != null) {
                            if (moved) onSeek(v.centerMs) else onSeek(xToTime(down.position.x, v, trackWidth))
                        }
                    }
                },
        ) {
            val v = vp ?: return@Canvas
            val wpx = size.width

            // Ticks + labels.
            for (t in ticks(v, wpx)) {
                val x = timeToX(t, v, wpx)
                if (x < 0f || x > wpx) continue // off-screen tick
                drawLine(Color.White.copy(alpha = 0.10f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                // Only label when there's room to its right. drawText sizes its layout from
                // (canvasWidth - topLeft.x); a label whose x sits at/past the right edge makes that
                // negative → IllegalArgumentException("maxWidth(-1) …") and crashes the whole player
                // while scrubbing. Guarding the label x keeps the tick line but drops the doomed text.
                if (x + 4f < wpx) {
                    drawText(
                        measurer,
                        clockTime(t),
                        topLeft = Offset(x + 4f, 4f),
                        style = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp),
                    )
                }
            }

            // Event chips.
            for (ev in events) {
                val x1 = timeToX(ev.startMs, v, wpx)
                val endT = ev.endMs ?: (ev.startMs + 30_000L)
                val w = (timeToX(endT, v, wpx) - x1).coerceAtLeast(3f)
                if (x1 + w < 0f || x1 > wpx) continue // off-screen
                drawRoundRect(
                    color = colorFor(ev.label),
                    topLeft = Offset(x1, size.height * 0.45f),
                    size = Size(w, size.height * 0.4f),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }

            // Playhead — at center while scrubbing, at the right edge while live.
            val px = timeToX(scrubTime, v, wpx)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                "Drag to scrub · pinch to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                vp?.let { "${formatSpan(visibleSpanMs(it, trackWidth).toLong())} view" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
