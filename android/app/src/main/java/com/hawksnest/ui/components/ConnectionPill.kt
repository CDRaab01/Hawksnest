package com.hawksnest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.ui.theme.HawksnestTheme

/** A compact connection-status chip: a colored dot + label. Never implies a stale "secure" state. */
@Composable
fun ConnectionPill(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    val (label, dot) = when (status) {
        ConnectionStatus.CONNECTED -> "Connected" to pulse.recovery
        ConnectionStatus.DEMO -> "Demo data" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.CONNECTING -> "Reconnecting" to pulse.streak
        ConnectionStatus.ERROR -> "Offline" to Color(0xFFFF5C5C)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(pulse.panelHigh)
            .padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(8.dp).clip(CircleShape).background(dot),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
