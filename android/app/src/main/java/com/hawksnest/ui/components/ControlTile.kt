package com.hawksnest.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * One tile in the Devices tab's lights-and-switches grid (Devices v3). A big touch target
 * for the most-toggled things in the house: name, room caption, and the shared optimistic
 * switch. Lit tiles take the strength-dim fill — the same state language as the compact
 * rows' icon discs, promoted to the whole surface. Tap opens the entity detail; the switch
 * toggles; long-press opens the actions sheet (rename / pin / hide).
 *
 * PULSE rules honored: hairline stroke + tone for depth (never elevation), tokens only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlTile(
    device: DeviceUi,
    caption: String,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val (shown, setTarget) = rememberOptimisticOnOff(device.rawState == "on", pending)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (shown) pulse.strengthDim else pulse.panel)
            .border(
                width = 1.dp,
                color = if (shown) pulse.strength.copy(alpha = 0.35f) else pulse.hairline,
                shape = MaterialTheme.shapes.medium,
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.sm),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = shown,
                onCheckedChange = {
                    if (it) haptics.toggleOn() else haptics.toggleOff()
                    setTarget(it)
                    onToggle(it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
            )
        }
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = if (shown) pulse.strength else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
