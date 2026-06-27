package com.hawksnest.ui.entity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.isZWaveDiagnostic
import com.hawksnest.core.logic.relativeTime
import com.hawksnest.core.logic.zwaveHealth
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.Sparkline
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private data class Range(val label: String, val hours: Int)

private val RANGES =
    listOf(Range("6h", 6), Range("24h", 24), Range("7d", 24 * 7), Range("30d", 24 * 30))

private val HIDDEN_ATTRS = setOf(
    "friendly_name", "icon", "supported_features", "supported_color_modes",
    "device_class", "entity_picture",
)

/**
 * Entity detail (drill-in): the live control card, a state-history chart with a 6h/24h/7d/30d toggle,
 * and a relevant-attributes list. Degrades to clear empty/error states instead of crashing. Ported
 * from the web `EntityScreen`.
 */
@Composable
fun EntityDetailScreen(
    onBack: () -> Unit,
    viewModel: EntityDetailViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsState()
    val hours by viewModel.hours.collectAsState()
    val history by viewModel.history.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val pulse = HawksnestTheme.pulse
    val channel = domainChannel(domainOf(viewModel.entityId), pulse)
    // Z-Wave node diagnostics read from the device's diagnostic siblings, shown as
    // a structured panel and removed from the raw Diagnostics dump below.
    val zwave = remember(diagnostics) {
        zwaveHealth(diagnostics.map { it.entityId to it.rawState })
    }
    val otherDiagnostics = remember(diagnostics) {
        diagnostics.filterNot { isZWaveDiagnostic(it.entityId) }
    }
    val hasZWave = zwave.nodeStatus != null || zwave.lastSeenMs != null || zwave.rttMs != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HawksnestTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device?.name ?: viewModel.entityId,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    viewModel.entityId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val current = device
        if (current == null) {
            Column(modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.lg)) {
                PanelCard {
                    Text(
                        "Device not found. It may be unavailable or hidden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        Column(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            SectionHeader("Control", channel = channel)
            DeviceControlCard(current, onCall = { service, extra -> viewModel.call(service, extra) })

            SectionHeader(
                "History",
                channel = channel,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
                        RANGES.forEach { r ->
                            RangeChip(r.label, active = hours == r.hours, channel = channel) { viewModel.setHours(r.hours) }
                        }
                    }
                },
            )
            PanelCard {
                when (val h = history) {
                    is HistoryUi.Loading -> ChartMessage("Loading history…")
                    is HistoryUi.Error -> ChartMessage("History isn't available for this device.")
                    is HistoryUi.Empty -> ChartMessage("Not enough history yet for this range.")
                    is HistoryUi.Data -> Sparkline(
                        points = h.levels,
                        channel = channel,
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    )
                }
            }

            if (hasZWave) {
                SectionHeader("Z-Wave", channel = if (zwave.dead) pulse.streak else channel)
                PanelCard {
                    if (zwave.dead) {
                        Text(
                            "This device has dropped off the Z-Wave mesh — it isn't responding to the controller.",
                            style = MaterialTheme.typography.labelMedium,
                            color = pulse.streak,
                            modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
                        )
                    }
                    zwave.nodeStatus?.let { status ->
                        ZWaveRow(
                            "Node status",
                            status.replaceFirstChar { c -> c.uppercaseChar() },
                            valueColor = when {
                                zwave.dead -> pulse.streak
                                status == "alive" -> pulse.recovery
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    zwave.lastSeenMs?.let { ZWaveRow("Last seen", relativeTime(it)) }
                    zwave.rttMs?.let { ZWaveRow("Signal (round-trip)", "$it ms") }
                }
            }

            if (otherDiagnostics.isNotEmpty()) {
                SectionHeader("Diagnostics", channel = channel)
                PanelCard {
                    otherDiagnostics.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                d.stateText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val attrs = current.attributes.entries
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                .filter { it.first !in HIDDEN_ATTRS }
            if (attrs.isNotEmpty()) {
                SectionHeader("Attributes", channel = channel)
                PanelCard {
                    attrs.forEach { (k, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(prettyKey(k), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                v,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZWaveRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChartMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RangeChip(label: String, active: Boolean, channel: Color, onClick: () -> Unit) {
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

/** Per-domain chart channel, mirroring each card's tint (web `DOMAIN_CHANNEL`). */
private fun domainChannel(domain: String, pulse: com.hawksnest.ui.theme.PulseColors): Color = when (domain) {
    "lock", "cover", "alarm_control_panel" -> pulse.recovery
    "light", "climate" -> pulse.strength
    "binary_sensor" -> pulse.streak
    else -> pulse.effort
}

private fun prettyKey(key: String): String =
    key.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
