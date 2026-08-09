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
import androidx.compose.runtime.rememberUpdatedState
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
import com.hawksnest.core.logic.DEFAULT_SPAN_MS
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.ClipEdge
import com.hawksnest.core.logic.ClipSelection
import com.hawksnest.core.logic.pickHandle
import com.hawksnest.core.logic.setEdge
import com.hawksnest.core.logic.FootageSpan
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
    /**
     * The 24/7 continuous track, as coalesced spans. Drawn as a low strip UNDER the event blocks:
     * the events are the moments worth looking at, the strip is the answer to "was anything even
     * recorded here?" — which, on a 24/7 camera, is yes across the whole day even where no event
     * fired. Empty for the battery cameras and the doorbell, which record events only.
     */
    footage: List<FootageSpan> = emptyList(),
    /** Streams the time under the playhead during an active drag (Compose delivers pointer events
     *  roughly per frame). Release always follows with onSeek/onLive. */
    onScrub: ((Long) -> Unit)? = null,
    /** Snap back to live — fired when a tap/drag lands in the "Live" region right of now. */
    onLive: () -> Unit = {},
    /**
     * The clip-export range being marked, or null when not in clip mode. Drawn as a band with a
     * draggable handle at each end. Optional so every existing call site is unaffected.
     */
    selection: ClipSelection? = null,
    /** The exportable range a dragged handle is clamped into (`ClipExport.exportBounds`). */
    selectionBounds: TimeWindow? = null,
    onSelectionChange: ((ClipSelection) -> Unit)? = null,
) {
    val pulse = HawksnestTheme.pulse
    val measurer = rememberTextMeasurer()
    val scrubTime = playhead ?: endMs

    var trackWidth by remember { mutableStateOf(0f) }
    var vp by remember { mutableStateOf<Viewport?>(null) }
    // True while a drag is emitting scrubs — suppresses the recenter effect, which would otherwise
    // chase every onScrub-driven playhead change and fight the finger (the web's drag guard).
    var gestureActive by remember { mutableStateOf(false) }

    // Read through `rememberUpdatedState` rather than capturing directly: a handle drag changes
    // `selection` on every pointer event, and putting it in the `pointerInput` key would tear the
    // gesture down and restart it mid-drag — the finger would lose the handle on the first move.
    val currentSelection = rememberUpdatedState(selection)
    val currentBounds = rememberUpdatedState(selectionBounds)
    val currentOnSelectionChange = rememberUpdatedState(onSelectionChange)

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
                // 64 -> 96dp. Arrived with the 2026-08-03 layout revision and KEPT when the rest
                // of that revision was reverted, on the owner's call: nothing in the feedback was
                // about the scrubber, and this is a drag target holding 24 hours, so every extra
                // dp is a wider grab area and more room for the event chips.
                //
                // The height is affordable because the picture cannot use it: a full-width 16:9
                // frame is ~26% of a 19.5:9 phone and that is simply what 16:9 is.
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .pointerInput(startMs, endMs, trackWidth) {
                    if (trackWidth <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // A press on a selection handle claims the whole gesture: no pan, no zoom,
                        // and crucially NO commit() on release. Falling through to the normal path
                        // would seek playback to wherever the handle was let go, which is the
                        // Android twin of the web's `data-clip-handle` escape hatch.
                        val sel0 = currentSelection.value
                        val vp0 = vp
                        val grabbed: ClipEdge? =
                            if (sel0 != null && vp0 != null && currentBounds.value != null) {
                                pickHandle(
                                    down.position.x,
                                    timeToX(sel0.startMs, vp0, trackWidth),
                                    timeToX(sel0.endMs, vp0, trackWidth),
                                )
                            } else null

                        if (grabbed != null) {
                            gestureActive = true
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                                val at = event.changes.firstOrNull()?.position?.x
                                val cur = currentSelection.value
                                val bounds = currentBounds.value
                                val v = vp
                                if (at != null && cur != null && bounds != null && v != null) {
                                    currentOnSelectionChange.value?.invoke(
                                        setEdge(cur, grabbed, xToTime(at, v, trackWidth), bounds),
                                    )
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            gestureActive = false
                            return@awaitEachGesture
                        }

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

            // 24/7 continuous track — a low strip along the bottom, below the event blocks (whose
            // band starts at 0.16h and ends at 0.84h, so the two never overlap). Translucent effort
            // rather than a second hue: it is the same material as the blocks, at lower emphasis.
            // A span that exists but can't be decoded (Ring end-to-end encryption) is drawn neutral,
            // so "recorded but unplayable" never masquerades as playable footage.
            val laneH = size.height * 0.09f
            val laneTop = size.height - laneH - size.height * 0.05f
            for (span in footage) {
                val x1 = timeToX(span.startMs, v, wpx)
                val w = (timeToX(span.endMs, v, wpx) - x1).coerceAtLeast(1f)
                if (x1 + w < 0f || x1 > wpx) continue // off-screen
                drawRoundRect(
                    color = if (span.playable) {
                        pulse.effort.copy(alpha = 0.40f)
                    } else {
                        Color.White.copy(alpha = 0.22f)
                    },
                    topLeft = Offset(x1, laneTop),
                    size = Size(w, laneH),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            // Clip-export selection — drawn under the event blocks so the chips stay legible over
            // it, and over the footage lane so the marked range reads against the footage it will
            // actually cut. Mirrors the web band + two handles.
            currentSelection.value?.let { sel ->
                val sx = timeToX(sel.startMs, v, wpx)
                val ex = timeToX(sel.endMs, v, wpx)
                // Dim what is NOT selected, the way the Live region dims the future.
                if (sx > 0f) {
                    drawRect(Color.Black.copy(alpha = 0.40f), Offset(0f, 0f), Size(sx, size.height))
                }
                if (ex < wpx) {
                    drawRect(
                        Color.Black.copy(alpha = 0.40f),
                        Offset(ex, 0f),
                        Size(wpx - ex, size.height),
                    )
                }
                drawRect(
                    pulse.recovery.copy(alpha = 0.25f),
                    Offset(sx, 0f),
                    Size((ex - sx).coerceAtLeast(2f), size.height),
                )
                for (hx in listOf(sx, ex)) {
                    drawLine(
                        pulse.recovery,
                        Offset(hx, 0f),
                        Offset(hx, size.height),
                        strokeWidth = 3f,
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
                // Say when the gaps between those moments are still watchable — otherwise a day
                // with few events reads as a day with little footage.
                "${events.size} moments" + if (footage.isNotEmpty()) " · 24/7" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
