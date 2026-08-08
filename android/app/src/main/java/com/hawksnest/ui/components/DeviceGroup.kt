package com.hawksnest.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CardType
import com.hawksnest.core.logic.ReadonlyItem
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * One device-group row in a read-only tier: same 44dp anatomy as the Devices tab's compact
 * rows, but standing for a whole physical device (a camera and its sensor spray). Shared by
 * the Devices tab and the Rooms → area detail so the two screens can never drift apart on
 * how a grouped device reads. Tap opens the member sheet; long-press is optional (the
 * Devices tab wires hide-all, area detail wires nothing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceGroupRow(
    group: ReadonlyItem.Group<DeviceUi>,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val pulse = HawksnestTheme.pulse
    val hasCamera = group.members.any { it.card == CardType.CAMERA }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.sm)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(pulse.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hasCamera) Icons.Filled.Videocam else Icons.Filled.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(HawksnestTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(group.members.size)
                    append(if (group.members.size == 1) " sensor" else " sensors")
                    if (group.activeCount > 0) {
                        append(" · ")
                        append(group.activeCount)
                        append(" active")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A device group's members: name + state each, tap-through to the entity detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceGroupSheet(
    group: ReadonlyItem.Group<DeviceUi>,
    onOpenEntity: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HawksnestTheme.pulse.panelHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HawksnestTheme.spacing.lg)
                .padding(bottom = HawksnestTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            group.members.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onOpenEntity(device.entityId) }
                        .padding(vertical = HawksnestTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            device.stateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
