package com.hawksnest.ui.area

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.CardType
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Area detail — the devices in one room with their essential controls (lock/unlock, alarm
 * arm/disarm, light on/off; everything else read-only for now). Non-optimistic: the UI reflects
 * HA's echo. The full domain-card set + history lands in Phase 2.
 */
@Composable
fun AreaDetailScreen(
    onBack: () -> Unit,
    viewModel: AreaDetailViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
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
            Text(
                viewModel.area,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            devices.forEach { device ->
                DeviceCard(device, onCall = { service -> viewModel.call(device.entityId, service) })
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceUi, onCall: (String) -> Unit) {
    val pulse = HawksnestTheme.pulse
    PanelCard {
        Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(device.stateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (device.card) {
            CardType.LOCK -> {
                ControlRow {
                    PulseButton(
                        text = "Lock", onClick = { onCall("lock") },
                        modifier = Modifier.weight(1f), tonal = true, compact = true,
                        channel = pulse.recovery, onChannel = pulse.onRecovery, dimChannel = pulse.recoveryDim,
                    )
                    PulseButton(
                        text = "Unlock", onClick = { onCall("unlock") },
                        modifier = Modifier.weight(1f), tonal = true, compact = true,
                        channel = pulse.streak, onChannel = pulse.onStreak, dimChannel = pulse.streakDim,
                    )
                }
            }
            CardType.LIGHT -> {
                val on = device.rawState == "on"
                ControlRow {
                    PulseButton(
                        text = if (on) "Turn off" else "Turn on",
                        onClick = { onCall(if (on) "turn_off" else "turn_on") },
                        modifier = Modifier.weight(1f), tonal = true, compact = true,
                        channel = pulse.effort, onChannel = pulse.onEffort, dimChannel = pulse.effortDim,
                    )
                }
            }
            CardType.ALARM -> {
                ControlRow {
                    ARM_BUTTONS.forEach { b ->
                        ArmSegment(
                            label = b.label,
                            active = device.rawState == b.state,
                            onClick = { onCall(b.service) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            else -> Unit // read-only (sensor / camera / cover / climate / media / fan come in Phase 2)
        }
    }
}

@Composable
private fun ControlRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Box(Modifier.padding(top = HawksnestTheme.spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm), content = content)
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
