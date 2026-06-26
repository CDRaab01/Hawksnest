package com.hawksnest.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Devices — a flat list of every device, grouped by room, each with its essential controls
 * (lock/light/alarm). The "all the switches" view.
 */
@Composable
fun DevicesScreen(
    onOpenArea: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader("Devices")
        groups.forEach { group ->
            Text(
                group.area.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
            )
            group.devices.forEach { device ->
                DeviceControlCard(device, onCall = { service -> viewModel.call(device.entityId, service) })
            }
        }
    }
}
