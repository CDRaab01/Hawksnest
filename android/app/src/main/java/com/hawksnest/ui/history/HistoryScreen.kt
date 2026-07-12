package com.hawksnest.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import com.hawksnest.ui.components.shimmer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.LogEvent
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class HistoryRange(val label: String, val hours: Int)

// 30d matches the backend recorder retention (MariaDB purge_keep_days: 30).
private val RANGES =
    listOf(HistoryRange("24h", 24), HistoryRange("7d", 24 * 7), HistoryRange("30d", 24 * 30))

// Float the most useful event domains to the front of the chip row.
private val DOMAIN_ORDER = listOf("camera", "binary_sensor", "lock", "alarm_control_panel", "light")

/**
 * History hub — a filterable, day-grouped activity timeline over HA's logbook. Range + category
 * chips narrow the feed; each event taps through to its entity. Ported from the web `HistoryScreen`
 * + `EventTimeline`.
 */
@Composable
fun HistoryScreen(
    onOpenEntity: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val hours by viewModel.hours.collectAsState()
    val domain by viewModel.domain.collectAsState()
    val feed by viewModel.feed.collectAsState()
    val pulse = HawksnestTheme.pulse

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader("Activity", channel = pulse.streak)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
        ) {
            RANGES.forEach { r ->
                Chip(r.label, active = hours == r.hours, channel = pulse.streak) { viewModel.setHours(r.hours) }
            }
        }

        when (val f = feed) {
            is HistoryFeed.Loading -> HistorySkeleton()
            is HistoryFeed.Error -> InfoCard("Couldn't load history.")
            is HistoryFeed.Loaded -> {
                val domains = presentDomains(f.events)
                if (domains.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
                    ) {
                        Chip("All", active = domain == "all", channel = pulse.effort) { viewModel.setDomain("all") }
                        domains.forEach { d ->
                            Chip(prettyDomain(d), active = domain == d, channel = pulse.effort) { viewModel.setDomain(d) }
                        }
                    }
                }
                val shown = if (domain == "all") f.events else f.events.filter { it.domain == domain }
                if (shown.isEmpty()) {
                    InfoCard("No events in this window.")
                } else {
                    groupByDay(shown).forEach { (label, events) ->
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
                        )
                        PanelCard {
                            events.forEach { ev -> EventRow(ev, pulse, onOpenEntity) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(ev: LogEvent, pulse: PulseColors, onOpenEntity: (String) -> Unit) {
    val rowMod = if (ev.entityId != null) {
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpenEntity(ev.entityId) }
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowMod.padding(vertical = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(domainChannel(ev.domain, pulse), CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ev.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            clockTime(ev.timeMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(text: String) {
    PanelCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chip(label: String, active: Boolean, channel: Color, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) channel else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) pulse.panelHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.xs),
    )
}

private fun presentDomains(events: List<LogEvent>): List<String> {
    val seen = events.mapNotNull { it.domain }.toSet()
    return seen.sortedWith(
        compareBy({ DOMAIN_ORDER.indexOf(it).let { i -> if (i == -1) 99 else i } }, { it }),
    )
}

private fun prettyDomain(domain: String): String =
    domain.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

private fun domainChannel(domain: String?, pulse: PulseColors): Color = when (domain) {
    "lock", "cover", "alarm_control_panel" -> pulse.recovery
    "light", "climate" -> pulse.strength
    "binary_sensor", "camera" -> pulse.streak
    else -> pulse.effort
}

private fun groupByDay(events: List<LogEvent>): List<Pair<String, List<LogEvent>>> {
    val zone = ZoneId.systemDefault()
    val out = mutableListOf<Pair<String, MutableList<LogEvent>>>()
    var currentKey: LocalDate? = null
    for (ev in events) {
        val d = Instant.ofEpochMilli(ev.timeMs).atZone(zone).toLocalDate()
        if (d != currentKey) {
            out.add(dayLabel(ev.timeMs) to mutableListOf())
            currentKey = d
        }
        out.last().second.add(ev)
    }
    return out
}

private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")

private fun dayLabel(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_FMT)
    }
}

private fun clockTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)

/** Loading placeholder: a column of shimmering timeline-row skeletons in one panel. */
@Composable
private fun HistorySkeleton() {
    PanelCard {
        repeat(6) { i ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = HawksnestTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
            ) {
                Box(Modifier.size(32.dp).shimmer(CircleShape))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
                ) {
                    Box(Modifier.height(14.dp).width((160 - i * 12).dp).shimmer(RoundedCornerShape(4.dp)))
                    Box(Modifier.height(11.dp).width(96.dp).shimmer(RoundedCornerShape(4.dp)))
                }
                Box(Modifier.height(11.dp).width(40.dp).shimmer(RoundedCornerShape(4.dp)))
            }
        }
    }
}
