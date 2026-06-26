package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * App doorbell banner — shown when a camera's `_ding` sensor rings (ring-mqtt).
 * **View** opens that camera's live player. Mirrors the web `DoorbellBanner`.
 * Show/auto-dismiss state is owned by the host screen.
 */
@Composable
fun DoorbellBanner(
    cameraName: String,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = HawksnestTheme.pulse.recovery)
            Column(Modifier.weight(1f)) {
                Text("Doorbell", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Someone's at $cameraName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onView,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HawksnestTheme.pulse.recovery,
                    contentColor = Color.Black,
                ),
            ) {
                Text("View")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
