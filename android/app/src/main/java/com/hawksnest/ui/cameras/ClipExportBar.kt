package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.ClipCoverage
import com.hawksnest.core.logic.ClipEdge
import com.hawksnest.core.logic.ClipProblem
import com.hawksnest.core.logic.ClipSelection
import com.hawksnest.core.logic.FootageSpan
import com.hawksnest.core.logic.NUDGE_COARSE_MS
import com.hawksnest.core.logic.NUDGE_FINE_MS
import com.hawksnest.core.logic.coverage
import com.hawksnest.core.logic.selectionDurationMs
import com.hawksnest.core.logic.selectionProblem
import com.hawksnest.ui.theme.HawksnestTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the export is going. [Started] means the file is saved. */
enum class ClipExportState {
    Idle,
    Preparing,
    Started,
    Failed,
}

private val BAR_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a")

private fun barClock(ms: Long): String =
    BAR_TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun durationLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * The sentences for each blocking problem — kept here, not in `ClipExport.kt`.
 *
 * The pure module returns an enum and never English, the same split `PlaceholderState` uses, so
 * the two platforms can word things idiomatically without the *rules* forking.
 */
private fun problemCopy(problem: ClipProblem): String =
    when (problem) {
        ClipProblem.TOO_SHORT -> "Clips must be at least 5 seconds."
        ClipProblem.TOO_LONG -> "Clips can be at most 10 minutes."
        ClipProblem.NO_FOOTAGE -> "No recording exists for this range."
    }

/**
 * Clip-export controls — the twin of the web `ClipExportBar`, replacing the transport while a range
 * is being marked.
 *
 * The interaction hierarchy is deliberate and matches the web. At the timeline's opening 1-hour
 * zoom one pixel is about ten seconds, so **dragging cannot place an edge accurately** and
 * "Start here"/"End here" are the primary instrument: scrub until the video shows the moment, then
 * mark it. The nudges are the fine adjustment (1s is ffmpeg's own granularity), and the handles on
 * the timeline are for gross positioning.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipExportBar(
    selection: ClipSelection,
    playheadMs: Long,
    footage: List<FootageSpan>,
    state: ClipExportState,
    error: String?,
    onNudge: (ClipEdge, Long) -> Unit,
    onSetEdgeToPlayhead: (ClipEdge) -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
) {
    val pulse = HawksnestTheme.pulse
    val problem = selectionProblem(selection, footage)
    val cover = coverage(selection, footage)
    val busy = state == ClipExportState.Preparing

    val status =
        error
            ?: problem?.let { problemCopy(it) }
            ?: when {
                cover == ClipCoverage.PARTIAL ->
                    "Part of this range wasn't recorded — the clip will be shorter."
                state == ClipExportState.Started -> "Saved to Movies/Hawksnest."
                else -> null
            }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (edge in listOf(ClipEdge.START, ClipEdge.END)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (edge == ClipEdge.START) "Start" else "End",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    barClock(if (edge == ClipEdge.START) selection.startMs else selection.endMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(84.dp),
                )
                BarChip("−15s") { onNudge(edge, -NUDGE_COARSE_MS) }
                BarChip("−1s") { onNudge(edge, -NUDGE_FINE_MS) }
                BarChip("+1s") { onNudge(edge, NUDGE_FINE_MS) }
                BarChip("+15s") { onNudge(edge, NUDGE_COARSE_MS) }
                BarChip(if (edge == ClipEdge.START) "Start here" else "End here") {
                    onSetEdgeToPlayhead(edge)
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                durationLabel(selectionDurationMs(selection)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // What "here" means — without it the two mark buttons aim at an unnamed moment.
            Text(
                "Playhead ${barClock(playheadMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            BarChip("Cancel", onClick = onCancel)
            if (state == ClipExportState.Started && onShare != null) {
                BarChip("Share", onClick = onShare)
            }
            BarChip(
                if (busy) "Saving…" else "Download",
                enabled = problem == null && !busy,
                accent = true,
                onClick = onDownload,
            )
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (error != null || problem != null) pulse.streak
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** The bar's chip look — mirrors `ChromeButton` in PlayerControls, tokens only, no hex. */
@Composable
private fun BarChip(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val bg = if (accent) pulse.recovery else MaterialTheme.colorScheme.surface
    val fg =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            accent -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        maxLines = 1,
        softWrap = false,
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (enabled) bg else bg.copy(alpha = 0.4f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
