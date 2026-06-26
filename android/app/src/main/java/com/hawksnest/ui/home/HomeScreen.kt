package com.hawksnest.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.AlarmView
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.color
import com.hawksnest.ui.theme.icon

/**
 * Home — the security-forward landing screen, mirroring the web Dashboard: a connection-aware
 * header, the arm/disarm hero with a Ring-style offline line, a **2-up camera grid** (like Ring),
 * the pinned favorites, and the area hub. Reads everything live from [HomeViewModel].
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hawksnest",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ConnectionPill(ui.status)
        }

        SecurityHero(
            alarm = ui.alarm,
            rawState = ui.alarmRawState,
            offlineLabel = ui.offlineLabel,
            onArm = { service ->
                ui.alarmEntityId?.let { viewModel.callService("alarm_control_panel", service, it) }
            },
        )

        if (ui.cameras.isNotEmpty()) {
            SectionHeader(
                title = "Cameras",
                channel = HawksnestTheme.pulse.effort,
                trailing = {
                    Text(
                        "${ui.liveCameraCount}/${ui.cameras.size} live",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            // Ring-style: two side-by-side tiles per row.
            ui.cameras.chunked(2).forEach { rowCams ->
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    rowCams.forEach { cam ->
                        CameraTile(cam.name, cam.live, Modifier.weight(1f))
                    }
                    if (rowCams.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (ui.favorites.isNotEmpty()) {
            SectionHeader("Home", channel = HawksnestTheme.pulse.effort)
            ui.favorites.forEach { fav ->
                FavoriteCard(fav, onArm = { service ->
                    viewModel.callService("alarm_control_panel", service, fav.entityId)
                }, onLock = { service ->
                    viewModel.callService("lock", service, fav.entityId)
                })
            }
        }

        if (ui.areas.isNotEmpty()) {
            SectionHeader("Areas", channel = HawksnestTheme.pulse.recovery)
            ui.areas.forEach { AreaRow(it) }
        }
    }
}

@Composable
private fun SecurityHero(
    alarm: AlarmView?,
    rawState: String?,
    offlineLabel: String?,
    onArm: (String) -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val channelColor = alarm?.let { pulse.color(it.channel) } ?: MaterialTheme.colorScheme.onSurface
    PanelCard(channel = alarm?.let { pulse.color(it.channel) }, raised = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alarm != null) {
                Icon(
                    imageVector = alarm.glyph.icon(),
                    contentDescription = null,
                    tint = channelColor,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.size(HawksnestTheme.spacing.md))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "SECURITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    alarm?.label ?: "No alarm panel",
                    style = MaterialTheme.typography.headlineSmall,
                    color = channelColor,
                )
            }
        }
        if (alarm != null) {
            Spacer(Modifier.size(HawksnestTheme.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                ARM_BUTTONS.forEach { b ->
                    ArmSegment(
                        label = b.label,
                        active = rawState == b.state,
                        onClick = { onArm(b.service) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (offlineLabel != null) {
            Spacer(Modifier.size(HawksnestTheme.spacing.md))
            Text(
                offlineLabel,
                style = MaterialTheme.typography.bodySmall,
                color = pulse.streak,
            )
        }
    }
}

@Composable
private fun ArmSegment(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) pulse.effortDim else pulse.panelHigh)
            .clickable(onClick = onClick)
            .padding(vertical = HawksnestTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraTile(name: String, live: Boolean, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    PanelCard(modifier = modifier, contentPadding = 0.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2A2F37), Color(0xFF0E1116))),
                ),
        ) {
            // Freshness badge (top-start)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(HawksnestTheme.spacing.sm)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) pulse.recovery else Color.White.copy(alpha = 0.4f)))
                Text(
                    if (live) "LIVE" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            // Name (bottom)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(HawksnestTheme.spacing.sm),
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun FavoriteCard(
    fav: FavoriteUi,
    onArm: (String) -> Unit,
    onLock: (String) -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    PanelCard {
        Text(fav.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(fav.stateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (fav.kind) {
            FavoriteKind.LOCK -> {
                Spacer(Modifier.size(HawksnestTheme.spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    PulseButton(
                        text = "Lock", onClick = { onLock("lock") },
                        modifier = Modifier.weight(1f), tonal = true, compact = true,
                        channel = pulse.recovery, onChannel = pulse.onRecovery, dimChannel = pulse.recoveryDim,
                    )
                    PulseButton(
                        text = "Unlock", onClick = { onLock("unlock") },
                        modifier = Modifier.weight(1f), tonal = true, compact = true,
                        channel = pulse.streak, onChannel = pulse.onStreak, dimChannel = pulse.streakDim,
                    )
                }
            }
            FavoriteKind.ALARM -> {
                Spacer(Modifier.size(HawksnestTheme.spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    ARM_BUTTONS.forEach { b ->
                        ArmSegment(
                            label = b.label,
                            active = fav.alarmState == b.state,
                            onClick = { onArm(b.service) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            FavoriteKind.OTHER -> Unit
        }
    }
}

@Composable
private fun AreaRow(area: AreaUi) {
    PanelCard(onClick = { /* Phase 1: area detail navigation */ }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(area.area, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${area.deviceCount} device${if (area.deviceCount == 1) "" else "s"} · ${area.preview}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
