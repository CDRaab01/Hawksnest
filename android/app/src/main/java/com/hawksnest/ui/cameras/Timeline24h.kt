package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.ui.theme.HawksnestTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun clockTime(ms: Long): String =
    TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The Ring-style scrubbable timeline. Recorded-event markers sit along a fixed `[startMs, endMs]`
 * track (a rolling 24h window); the playhead (right edge = live) shows where we are. Tapping the
 * track seeks; tapping a marker jumps to that event. Colors follow the detected object's PULSE
 * channel. Mirrors the web `Timeline24h`.
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
    val span = (endMs - startMs).coerceAtLeast(1L)
    val pulse = HawksnestTheme.pulse

    fun colorFor(label: String): Color = when (label) {
        "person" -> pulse.strength
        "car", "truck" -> pulse.effort
        "dog", "cat" -> pulse.recovery
        else -> pulse.streak
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(
                "Last 24 hours",
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

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(startMs, endMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(startMs + (frac * span).toLong())
                    }
                },
        ) {
            val full = maxWidth
            events.forEach { ev ->
                val leftFrac = ((ev.startMs - startMs).toFloat() / span).coerceIn(0f, 1f)
                val endT = ev.endMs ?: (ev.startMs + 30_000L)
                val wFrac = ((endT - ev.startMs).toFloat() / span).coerceAtLeast(0.006f)
                Box(
                    Modifier
                        .offset(x = full * leftFrac)
                        .width(full * wFrac)
                        .fillMaxHeight(0.6f)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorFor(ev.label))
                        .clickable { onSeek(ev.startMs) },
                )
            }

            val headFrac = if (playhead == null) 1f else ((playhead - startMs).toFloat() / span).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset(x = full * headFrac - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                clockTime(startMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Live",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
