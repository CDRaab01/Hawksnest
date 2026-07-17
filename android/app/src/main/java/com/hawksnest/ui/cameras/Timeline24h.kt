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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
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
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

private fun clockTime(ms: Long): String =
    TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Ring's centered header: "TODAY" for today, otherwise the scrubbed day's date. */
private fun dayHeader(ms: Long): String {
    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> DATE_FMT.format(day).uppercase()
    }
}

/** Opening zoom: ~8h visible so the day reads at a glance (Ring-like), clamped into [10min, 24h]. */
private const val DEFAULT_SPAN_MS = 8 * HOUR_MS
private const val TAP_SLOP_PX = 8f

/**
 * Ring-style scrubbable timeline: a center-anchored, zoomable + pannable strip drawn on a Canvas.
 * Drag left/right to move through time; pinch to zoom (≈10 min → 24 h). The playhead marks the
 * current time — pinned at center while scrubbing, at the right edge while live. While dragging,
 * [onScrub] streams the time under the center playhead so the parent can preview footage live;
 * release still commits through [onSeek]/[onLive]. A clean tap seeks to the tapped time. All the
 * mapping/clamp math lives in `core/logic/TimelineViewport`. Mirrors the web `Timeline24h`.
 */
@Composable
fun Timeline24h(
    events: List<CameraEvent>,
    startMs: Long,
    endMs: Long,
    playhead: Long?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Streams the time under the playhead during an active drag (Compose delivers pointer events
     *  roughly per frame). Release always follows with onSeek/onLive. */
    onScrub: ((Long) -> Unit)? = null,
    /** Snap back to live — fired when a tap/drag lands in the "Live" region right of now. */
    onLive: () -> Unit = {},
) {
    val pulse = HawksnestTheme.pulse
    val measurer = rememberTextMeasurer()
    val scrubTime = playhead ?: endMs

    var trackWidth by remember { mutableStateOf(0f) }
    var vp by remember { mutableStateOf<Viewport?>(null) }
    // True while a drag is emitting scrubs — suppresses the recenter effect, which would otherwise
    // chase every onScrub-driven playhead change and fight the finger (the web's drag guard).
    var gestureActive by remember { mutableStateOf(false) }

    // The clamp window is padded past *now* by half the visible span, so "now" can sit at CENTER
    // with the "Live" region filling the right half — the Ring layout. (Unpadded, the clamp pins
    // now to the right edge and the Live region could never show.) Panning right naturally stops
    // when now reaches center. Mirrors the web `paddedWindow`.
    fun padded(v: Viewport?): TimeWindow {
        val half = (
            (v?.takeIf { trackWidth > 0f }?.let { visibleSpanMs(it, trackWidth) }
                ?: DEFAULT_SPAN_MS.toDouble()) / 2
            ).toLong()
        return TimeWindow(startMs, endMs + half)
    }

    // Commit a scrub/tap time: at/past *now* means the Live region — snap back to live.
    fun commit(ms: Long) {
        if (ms >= endMs) onLive() else onSeek(minOf(ms, endMs))
    }

    // Re-center on external seeks (Live / prev / next) and width changes, preserving zoom.
    // Suppressed while a drag is scrubbing — the viewport is already under the finger.
    LaunchedEffect(playhead, trackWidth, startMs, endMs) {
        if (trackWidth > 0f && !gestureActive) {
            val span = vp?.let { visibleSpanMs(it, trackWidth).toLong() } ?: DEFAULT_SPAN_MS
            vp = viewportForSpan(scrubTime, span, trackWidth, padded(vp))
        }
    }

    Column(modifier) {
        Text(
            dayHeader(scrubTime),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
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
                                    nv = zoom(nv, zoomChange, trackWidth, padded(cur))
                                    moved = true
                                }
                                if (panChange.x != 0f) {
                                    nv = pan(nv, panChange.x, trackWidth, padded(cur))
                                    totalDx += panChange.x
                                    if (abs(totalDx) > TAP_SLOP_PX) moved = true
                                }
                                if (nv != cur) {
                                    vp = nv
                                    // Live scrub: stream the center time while panning (clamped
                                    // out of the Live region).
                                    if (moved) {
                                        gestureActive = true
                                        onScrub?.invoke(minOf(nv.centerMs, endMs))
                                    }
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        gestureActive = false
                        val v = vp
                        if (v != null) {
                            if (moved) commit(v.centerMs) else commit(xToTime(down.position.x, v, trackWidth))
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

            // Recording blocks — solid effort-blue, tall like Ring's; every block is a playable clip.
            val blockTop = size.height * 0.16f
            val blockH = size.height * 0.68f
            for (ev in events) {
                val x1 = timeToX(ev.startMs, v, wpx)
                val endT = ev.endMs ?: (ev.startMs + 30_000L)
                val w = (timeToX(endT, v, wpx) - x1).coerceAtLeast(3f)
                if (x1 + w < 0f || x1 > wpx) continue // off-screen
                drawRoundRect(
                    color = pulse.effort,
                    topLeft = Offset(x1, blockTop),
                    size = Size(w, blockH),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }

            // "Live" region — everything to the right of now (endMs) is the not-yet-recorded future;
            // dim it and label it, so the centered playhead reads as "now" (the Ring layout).
            val nowX = timeToX(endMs, v, wpx)
            if (nowX < wpx) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(nowX, 0f),
                    size = Size(wpx - nowX, size.height),
                )
                drawLine(pulse.recovery, Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 1.5f)
                val liveLayout = measurer.measure(
                    "Live",
                    style = TextStyle(color = pulse.recovery, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
                val regionW = wpx - nowX
                if (regionW > liveLayout.size.width + 8f) {
                    drawText(
                        liveLayout,
                        topLeft = Offset(
                            nowX + (regionW - liveLayout.size.width) / 2f,
                            (size.height - liveLayout.size.height) / 2f,
                        ),
                    )
                }
            }

            // Playhead — inward-pointing triangles top & bottom on a hairline (Ring's marker), pinned
            // at center while scrubbing, at the right edge (now) while live.
            val px = timeToX(scrubTime, v, wpx)
            val tw = 6.dp.toPx()
            val th = 7.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.9f), Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
            drawPath(
                Path().apply {
                    moveTo(px - tw, 0f); lineTo(px + tw, 0f); lineTo(px, th); close()
                },
                Color.White,
            )
            drawPath(
                Path().apply {
                    moveTo(px - tw, size.height); lineTo(px + tw, size.height); lineTo(px, size.height - th); close()
                },
                Color.White,
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                if (playhead == null) "Live" else clockTime(scrubTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${events.size} moments",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
