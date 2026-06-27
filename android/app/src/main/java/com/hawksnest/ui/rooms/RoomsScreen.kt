package com.hawksnest.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.RoomHighlight
import com.hawksnest.core.logic.RoomStat
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseColors

/**
 * Rooms — the area hub, a 2-column grid of room tiles. Each tile has a per-room icon, a stable
 * channel accent keyed to the room *type* (so a room's color never shifts as rooms come and go), and
 * a few at-a-glance highlight chips (unlocked / motion / lights on / cameras / temp) → area detail.
 */
@Composable
fun RoomsScreen(
    onOpenArea: (String) -> Unit,
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val rooms by viewModel.rooms.collectAsState()
    val pulse = HawksnestTheme.pulse
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(HawksnestTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("Rooms", channel = pulse.recovery)
        }
        items(rooms, key = { it.area }) { room ->
            RoomCard(room = room, onClick = { onOpenArea(room.area) })
        }
    }
}

@Composable
private fun RoomCard(room: RoomUi, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    val channel = roomChannel(room.iconKey)
    val accent = pulse.base(channel)
    PanelCard(onClick = onClick, channel = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(pulse.dim(channel)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    roomIcon(room.iconKey),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                room.area,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${room.deviceCount} device${if (room.deviceCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (room.highlights.isNotEmpty()) {
                HighlightRow(room.highlights)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlightRow(highlights: List<RoomHighlight>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
    ) {
        highlights.forEach { HighlightChip(it) }
    }
}

@Composable
private fun HighlightChip(highlight: RoomHighlight) {
    val pulse = HawksnestTheme.pulse
    val accent = pulse.base(highlight.channel)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(pulse.dim(highlight.channel))
            .padding(horizontal = HawksnestTheme.spacing.sm, vertical = 4.dp),
    ) {
        Icon(statIcon(highlight.stat), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Text(highlight.label, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

/**
 * Stable per-room-*type* accent (keyed to the icon/type, not list position) so a room always wears
 * the same color regardless of how many rooms exist or their order. There are only four PULSE
 * channels, so related room types are grouped onto a sensible one:
 *  - streak (warm):  kitchen, dining, laundry
 *  - effort (blue):  bath, front door, garage, security
 *  - strength (violet): bedroom, office, living, basement
 *  - recovery (green):  outdoor + everything unclassified
 */
private fun roomChannel(iconKey: String): Channel = when (iconKey) {
    "kitchen", "dining", "laundry" -> Channel.STREAK
    "bath", "frontdoor", "garage", "security" -> Channel.EFFORT
    "bedroom", "office", "living", "basement" -> Channel.STRENGTH
    else -> Channel.RECOVERY // outdoor, unassigned, default
}

private fun PulseColors.base(channel: Channel): Color = when (channel) {
    Channel.EFFORT -> effort
    Channel.STRENGTH -> strength
    Channel.STREAK -> streak
    Channel.RECOVERY -> recovery
}

private fun PulseColors.dim(channel: Channel): Color = when (channel) {
    Channel.EFFORT -> effortDim
    Channel.STRENGTH -> strengthDim
    Channel.STREAK -> streakDim
    Channel.RECOVERY -> recoveryDim
}

private fun roomIcon(key: String): ImageVector = when (key) {
    "kitchen" -> Icons.Filled.Kitchen
    "dining" -> Icons.Filled.Restaurant
    "bath" -> Icons.Filled.Bathtub
    "bedroom" -> Icons.Filled.KingBed
    "garage" -> Icons.Filled.Garage
    "office" -> Icons.Filled.Work
    "living" -> Icons.Filled.Weekend
    "basement" -> Icons.Filled.Stairs
    "laundry" -> Icons.Filled.LocalLaundryService
    "frontdoor" -> Icons.Filled.DoorFront
    "outdoor" -> Icons.Filled.Yard
    "security" -> Icons.Filled.Shield
    "unassigned" -> Icons.Filled.Apps
    else -> Icons.Filled.MeetingRoom
}

private fun statIcon(stat: RoomStat): ImageVector = when (stat) {
    RoomStat.UNLOCKED -> Icons.Filled.LockOpen
    RoomStat.MOTION -> Icons.Filled.Sensors
    RoomStat.LIGHTS -> Icons.Filled.Lightbulb
    RoomStat.CAMERAS -> Icons.Filled.Videocam
    RoomStat.TEMPERATURE -> Icons.Filled.Thermostat
}
