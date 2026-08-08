package com.hawksnest.ui.area

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.ReadonlyItem
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.DeviceGroupRow
import com.hawksnest.ui.components.DeviceGroupSheet
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Area detail — the devices in one room. Controls (featured + control tiers) keep their full
 * [DeviceControlCard]s: this screen is the room's control surface, richer by design than the
 * Devices tab's compact rows. Security domains stay non-optimistic (pending until HA's echo);
 * toggles render optimistically and reconcile. The read-only tail is device-grouped, exactly
 * like the Devices tab: single sensors keep their cards, a device shedding several read-only
 * entities collapses into one shared [DeviceGroupRow] that opens the member sheet.
 */
@Composable
fun AreaDetailScreen(
    onBack: () -> Unit,
    onOpenEntity: (String) -> Unit,
    viewModel: AreaDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val pending by viewModel.pending.collectAsState()
    var groupSheet by remember { mutableStateOf<ReadonlyItem.Group<DeviceUi>?>(null) }

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
            ui.controls.forEach { device ->
                DeviceControlCard(
                    device,
                    onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                    onOpen = { onOpenEntity(device.entityId) },
                    pending = device.entityId in pending,
                )
            }
            // Ungrouped sensors keep their full cards; grouped devices collapse below.
            ui.readonly.forEach { item ->
                if (item is ReadonlyItem.Single) {
                    DeviceControlCard(
                        item.device,
                        onCall = { service, extra -> viewModel.call(item.device.entityId, service, extra) },
                        onOpen = { onOpenEntity(item.device.entityId) },
                        pending = item.device.entityId in pending,
                    )
                }
            }
            val groups = ui.readonly.filterIsInstance<ReadonlyItem.Group<DeviceUi>>()
            if (groups.isNotEmpty()) {
                PanelCard {
                    groups.forEachIndexed { i, group ->
                        if (i > 0) {
                            HorizontalDivider(color = HawksnestTheme.pulse.hairline, thickness = 1.dp)
                        }
                        DeviceGroupRow(group = group, onOpen = { groupSheet = group })
                    }
                }
            }
        }
    }

    groupSheet?.let { group ->
        DeviceGroupSheet(
            group = group,
            onOpenEntity = {
                groupSheet = null
                onOpenEntity(it)
            },
            onDismiss = { groupSheet = null },
        )
    }
}
