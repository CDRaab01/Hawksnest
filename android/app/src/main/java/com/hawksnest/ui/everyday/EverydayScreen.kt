package com.hawksnest.ui.everyday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hawksnest.core.logic.EverydayCandidate
import com.hawksnest.core.logic.EverydayDay
import com.hawksnest.core.logic.EverydaySummary
import com.hawksnest.core.logic.everydayCameraLabel
import com.hawksnest.core.logic.everydayDayLabel
import com.hawksnest.core.logic.everydayStreak
import com.hawksnest.core.logic.everydayTimeLabel
import com.hawksnest.core.logic.shown
import com.hawksnest.ui.cameras.VideoPlayer
import com.hawksnest.ui.components.LoadingState
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * "One second a day" — Kotlin twin of the web `EverydayScreen`: a strip of recent days, the
 * selected day's clip looping silently, the top-5 candidates to preview and swap, and the
 * yearly reels. Reached from Home's header (film icon beside the gear).
 */
@Composable
fun EverydayScreen(
    onBack: (() -> Unit)? = null,
    viewModel: EverydayViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    EverydayContent(
        ui = ui,
        onBack = onBack,
        onSelect = viewModel::select,
        onPreview = viewModel::preview,
        onChoose = viewModel::choose,
        onRescan = viewModel::rescan,
        onDownload = { viewModel.downloadReel(context, it) },
    )
}

/** Stateless body — what previews and tests render. */
@Composable
fun EverydayContent(
    ui: EverydayUi,
    onBack: (() -> Unit)? = null,
    onSelect: (String) -> Unit = {},
    onPreview: (String) -> Unit = {},
    onChoose: (String) -> Unit = {},
    onRescan: () -> Unit = {},
    onDownload: (com.hawksnest.core.logic.EverydayReel) -> Unit = {},
) {
    val spacing = HawksnestTheme.spacing
    val pulse = HawksnestTheme.pulse
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "One second a day",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (ui.demo) {
                Text(
                    "SAMPLE DATA",
                    style = MaterialTheme.typography.labelSmall,
                    color = pulse.streak,
                    modifier = Modifier
                        .background(pulse.streakDim, MaterialTheme.shapes.small)
                        .padding(horizontal = spacing.sm, vertical = spacing.xs),
                )
            }
        }

        val streak = everydayStreak(ui.days)
        SectionHeader(
            "Recent days",
            trailing = if (streak > 0) {
                {
                    Text(
                        "$streak-day streak",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        ui.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when {
            ui.loading -> LoadingState(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            ui.days.isEmpty() -> PanelCard(Modifier.fillMaxWidth()) {
                Text(
                    "Nothing yet. The first pick is made tonight at 9 pm.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                items(ui.days, key = { it.date }) { d ->
                    DayChip(d, active = d.date == ui.selected, ui = ui, onClick = { onSelect(d.date) })
                }
            }
        }

        ui.day?.let { day -> DayPanel(day, ui, onPreview, onChoose, onRescan) }

        if (ui.reels.isNotEmpty()) {
            SectionHeader("Reels", channel = pulse.streak)
            ui.reels.forEach { r ->
                PanelCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.year}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "  " + (if (r.label.isNotBlank()) "${r.label} · " else "") + "${r.days} days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        PulseButton(
                            text = "Download",
                            onClick = { onDownload(r) },
                            tonal = true,
                            compact = true,
                            enabled = !ui.busy,
                            channel = pulse.streak,
                            dimChannel = pulse.streakDim,
                            leadingIcon = { Icon(Icons.Filled.Download, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayChip(day: EverydaySummary, active: Boolean, ui: EverydayUi, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = pulse.panel,
        border = BorderStroke(1.dp, if (active) pulse.effort else pulse.hairline),
        modifier = Modifier
            .width(112.dp)
            .semantics { contentDescription = "Day ${day.date}" },
    ) {
        Column(Modifier.padding(HawksnestTheme.spacing.xs)) {
            Thumb(day.pick, ui)
            Text(
                everydayDayLabel(day.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = HawksnestTheme.spacing.xs),
            )
            Text(
                when {
                    day.provisional -> "Provisional"
                    day.manual -> "Your pick"
                    else -> "Auto"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (day.manual) pulse.recovery else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Thumbnail with the HA token header; demo (empty thumb) draws a tinted placeholder per camera. */
@Composable
private fun Thumb(c: EverydayCandidate?, ui: EverydayUi) {
    val shape = MaterialTheme.shapes.small
    val mod = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
    if (c != null && c.thumb.isNotBlank() && ui.token != null) {
        val ctx = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data("${ui.baseUrl.trimEnd('/')}/everyday/${c.thumb}")
                .addHeader("Authorization", "Bearer ${ui.token}")
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = mod.background(HawksnestTheme.pulse.panelHigh),
        )
    } else {
        Box(mod.background(placeholderBrush(c?.camera)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun placeholderBrush(camera: String?): Brush {
    val p = HawksnestTheme.pulse
    val tones = listOf(p.effort, p.recovery, p.strength, p.streak)
    val c = tones[((camera?.hashCode() ?: 0) and 0x7fffffff) % tones.size]
    return Brush.linearGradient(listOf(c.copy(alpha = 0.55f), p.panelHigh))
}

@Composable
private fun DayPanel(
    day: EverydayDay,
    ui: EverydayUi,
    onPreview: (String) -> Unit,
    onChoose: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val spacing = HawksnestTheme.spacing
    val pulse = HawksnestTheme.pulse
    val shown = day.shown(ui.preview)
    PanelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Movie, null, tint = pulse.effort, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = spacing.sm)) {
                    Text(everydayDayLabel(day.date), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${day.eventCount} events · ${if (day.provisional) "provisional" else "final"}" +
                            if (day.genai) "" else " · heuristics only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRescan, enabled = !ui.busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (shown == null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, pulse.hairline, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) { Text("No clip for this day", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val url = if (ui.token == null) shown.clip else "${ui.baseUrl.trimEnd('/')}/everyday/${shown.clip}"
                VideoPlayer(
                    url = url,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small),
                    loop = true,
                    muted = true,
                    authToken = ui.token,
                )
                val isPick = shown.eventId == day.pick
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${everydayCameraLabel(shown.camera)} · ${everydayTimeLabel(shown.peak)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        shown.aiScore?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                                Text(
                                    " ${it.toInt()}/10",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (isPick) {
                        Icon(Icons.Filled.CheckCircle, null, tint = pulse.recovery, modifier = Modifier.size(16.dp))
                        Text(
                            if (day.manual) " Your pick" else " Auto pick",
                            style = MaterialTheme.typography.labelMedium,
                            color = pulse.recovery,
                        )
                    } else {
                        PulseButton(text = "Use this one", onClick = { onChoose(shown.eventId) }, compact = true, enabled = !ui.busy)
                    }
                }
                if (shown.description.isNotBlank()) {
                    Text(shown.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Candidates: 2-up rows (5 across is too tight on a phone).
            day.candidates.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    row.forEach { c ->
                        CandidateTile(c, ui, selected = c.eventId == shown?.eventId, picked = c.eventId == day.pick, onClick = { onPreview(c.eventId) }, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CandidateTile(
    c: EverydayCandidate,
    ui: EverydayUi,
    selected: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, if (selected) pulse.effort else pulse.hairline, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Preview ${everydayCameraLabel(c.camera)} at ${everydayTimeLabel(c.peak)}" }
            .padding(HawksnestTheme.spacing.xs),
    ) {
        Thumb(c, ui)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = HawksnestTheme.spacing.xs)) {
            if (picked) Icon(Icons.Filled.CheckCircle, null, tint = pulse.recovery, modifier = Modifier.size(12.dp).padding(end = 2.dp))
            Text(
                everydayCameraLabel(c.camera),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(everydayTimeLabel(c.peak), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
