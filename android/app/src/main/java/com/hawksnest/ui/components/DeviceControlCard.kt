package com.hawksnest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.CardType
import com.hawksnest.ui.theme.HawksnestTheme

/** A controllable entity for list/detail rendering. */
data class DeviceUi(
    val entityId: String,
    val name: String,
    val stateText: String,
    val rawState: String,
    val card: CardType,
)

/**
 * One device row with its essential controls — lock/unlock, alarm arm/disarm, light on/off;
 * everything else is read-only for now. Non-optimistic: callers route [onCall] through
 * `ConnectionManager.callService`, and the store reconciles from HA's echo. Shared by the Devices
 * tab and the per-room Area detail. (Full domain cards + history land in Phase 2.)
 */
@Composable
fun DeviceControlCard(device: DeviceUi, onCall: (String) -> Unit) {
    val pulse = HawksnestTheme.pulse
    PanelCard {
        Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(device.stateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (device.card) {
            CardType.LOCK -> ControlRow {
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
            CardType.ALARM -> ControlRow {
                ARM_BUTTONS.forEach { b ->
                    ArmSegment(
                        label = b.label,
                        active = device.rawState == b.state,
                        onClick = { onCall(b.service) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
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
