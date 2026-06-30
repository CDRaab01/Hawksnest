package com.hawksnest.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.alarmView
import com.hawksnest.core.logic.relativeTime
import com.hawksnest.ui.cameras.CameraLightbox
import com.hawksnest.ui.cameras.DoorbellBanner
import com.hawksnest.ui.cameras.CameraSnapshot
import com.hawksnest.ui.cameras.bustCache
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.color
import kotlinx.coroutines.delay

/** Per-arm-mode glyph (Ring uses a distinct icon per mode). */
private val ARM_ICON: Map<String, ImageVector> = mapOf(
    "alarm_disarm" to Icons.Filled.LockOpen,
    "alarm_arm_home" to Icons.Filled.Home,
    "alarm_arm_away" to Icons.Filled.Lock,
)

/**
 * Home — a glanceable, camera-forward landing screen (Ring-style), mirroring the web Dashboard:
 * three big circular arm buttons + a one-line security read-out, the 2-up camera grid, and a single
 * compact "Rooms" entry. Device controls live one tap deeper (Rooms → area detail).
 */
@Composable
fun HomeScreen(
    onOpenRooms: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    // One ticking bucket shared by every tile so all snapshots refresh on the same ~10s beat
    // (matches the web SnapshotBucketProvider; Ring rate-limits the proxy, so fewer fetches help).
    val bucket by produceState(0L) {
        while (true) {
            delay(10_000)
            value += 1   // monotonic — a backward clock jump can't repeat a bucket
        }
    }
    var lightbox by remember { mutableStateOf<CameraUi?>(null) }

    // Doorbell banner: show the latest ring until dismissed or auto-timeout.
    var doorbellDismissedAt by remember { mutableStateOf(0L) }
    val ring = ui.doorbell
    val showDoorbell = ring != null && ring.whenMs > doorbellDismissedAt
    LaunchedEffect(showDoorbell, ring?.whenMs) {
        if (showDoorbell && ring != null) {
            kotlinx.coroutines.delay(12_000)
            doorbellDismissedAt = ring.whenMs
        }
    }

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
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showDoorbell && ring != null) {
            DoorbellBanner(
                cameraName = ring.name,
                onView = {
                    ui.cameras.firstOrNull { it.id == ring.cameraId }?.let { lightbox = it }
                    doorbellDismissedAt = ring.whenMs
                },
                onDismiss = { doorbellDismissedAt = ring.whenMs },
            )
        }

        if (ui.lifeSafetyAlerts.isNotEmpty() || ui.lifeSafetyMonitored > 0) {
            LifeSafetyStrip(ui)
        }

        SecurityHero(
            ui,
            onArm = viewModel::arm,
            onDisarm = { viewModel.arm("alarm_disarm") },
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
            ui.cameras.chunked(2).forEach { rowCams ->
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    rowCams.forEach { cam ->
                        CameraTile(
                            cam = cam,
                            snapshotModel = bustCache(cam.snapshotUrl, bucket),
                            onClick = { lightbox = cam },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCams.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (ui.roomCount > 0) {
            SectionHeader("Rooms", channel = HawksnestTheme.pulse.recovery)
            PanelCard(onClick = onOpenRooms) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${ui.roomCount} ${if (ui.roomCount == 1) "room" else "rooms"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            ui.roomsPreview,
                            style = MaterialTheme.typography.bodyMedium,
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
    }

    lightbox?.let { cam ->
        CameraLightbox(
            cameras = ui.cameras,
            initial = cam,
            onDismiss = { lightbox = null },
        )
    }
}

/**
 * Life-safety (smoke/CO/gas/leak) — an always-on channel surfaced regardless of armed state. A
 * triggered sensor shows a prominent streak-channel alert; otherwise a quiet monitored "all clear".
 */
@Composable
private fun LifeSafetyStrip(ui: HomeUi) {
    val pulse = HawksnestTheme.pulse
    val alert = ui.lifeSafetyAlerts.isNotEmpty()
    val channel = if (alert) pulse.streak else pulse.recovery
    PanelCard(channel = channel) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(channel))
            Spacer(Modifier.size(HawksnestTheme.spacing.sm))
            Text(
                if (alert) {
                    "Life-safety: ${ui.lifeSafetyAlerts.joinToString(" · ")}"
                } else {
                    "Life-safety: all clear · ${ui.lifeSafetyMonitored} monitored"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (alert) channel else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SecurityHero(ui: HomeUi, onArm: (String) -> Unit, onDisarm: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    PanelCard(channel = ui.alarm?.let { pulse.color(it.channel) }, raised = true) {
        if (ui.alarm != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xl, Alignment.CenterHorizontally),
            ) {
                ARM_BUTTONS.forEach { b ->
                    ArmCircle(
                        label = b.label,
                        icon = ARM_ICON[b.service] ?: Icons.Filled.LockOpen,
                        active = ui.alarmRawState == b.state,
                        channel = pulse.color(alarmView(b.state).channel),
                        onClick = {
                            if (b.service == "alarm_disarm") onDisarm() else onArm(b.service)
                        },
                    )
                }
            }
        } else {
            Text(
                "No alarm panel",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(HawksnestTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                ui.securitySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.secureAllClear) pulse.recovery else pulse.streak,
            )
            ui.offlineLabel?.let {
                Text(
                    " · $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = pulse.streak,
                )
            }
        }
    }
}

@Composable
private fun ArmCircle(
    label: String,
    icon: ImageVector,
    active: Boolean,
    channel: Color,
    onClick: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (active) channel else pulse.panelHigh)
                .then(
                    if (active) Modifier
                    else Modifier.border(1.dp, pulse.hairline, CircleShape),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.size(HawksnestTheme.spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) channel else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraTile(
    cam: CameraUi,
    snapshotModel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val name = cam.name
    val live = cam.live
    PanelCard(modifier = modifier, contentPadding = 0.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clickable(onClick = onClick),
        ) {
            CameraSnapshot(model = snapshotModel, modifier = Modifier.fillMaxSize())
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
                // Ring-style: stamp the snapshot's age (the tile is a still, not a live
                // feed — tapping it opens live), falling back to LIVE/— if we have no time.
                Text(
                    cam.lastChangedMs?.let { relativeTime(it) } ?: if (live) "LIVE" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
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
