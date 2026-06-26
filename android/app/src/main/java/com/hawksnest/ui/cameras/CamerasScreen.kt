package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Phase 0 placeholder. Phase 3 fills this with the snapshot grid (Coil, periodic refresh) that
 * promotes to a live MJPEG stream on tap.
 */
@Composable
fun CamerasScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader("Cameras")
        PanelCard {
            Text(
                "Camera snapshots will appear here once Home Assistant is connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
