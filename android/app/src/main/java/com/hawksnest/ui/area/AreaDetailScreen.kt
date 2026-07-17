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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Area detail — the devices in one room with their essential controls (via the shared
 * [DeviceControlCard]). Security domains stay non-optimistic (pending until HA's echo); toggles
 * render optimistically and reconcile. See the control model notes on [DeviceControlCard].
 */
@Composable
fun AreaDetailScreen(
    onBack: () -> Unit,
    onOpenEntity: (String) -> Unit,
    viewModel: AreaDetailViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val pending by viewModel.pending.collectAsState()
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
                DeviceControlCard(
                    device,
                    onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                    onOpen = { onOpenEntity(device.entityId) },
                    pending = device.entityId in pending,
                )
            }
        }
    }
}
