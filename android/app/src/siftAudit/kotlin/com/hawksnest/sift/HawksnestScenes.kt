package com.hawksnest.sift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.DataText
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.StatTile

/**
 * Representative Hawksnest scenes for the Sift design-slop audit. These render the **real**
 * `HawksnestTheme` (so the Space Grotesk / Inter / JetBrains Mono type scale and the PULSE palette
 * are authentic), the **real** reusable components (`PanelCard`, `SectionHeader`, `StatTile`,
 * `DataText`, `ConnectionPill`), and **real** product copy (the alarm labels from
 * `core/logic/Alarm.kt`, the security read-out, the demo-mode notice). They mirror the on-device
 * screens closely enough that the token/copy/contrast/touch-target rules see what a user sees,
 * while staying free of Hilt/ViewModels/network so the render is deterministic on Robolectric.
 *
 * This is the same "assemble scenes like screenshot tests" pattern Sift's own `samples` use, and the
 * pattern the `/sift` skill documents for adopting Sift on an existing app.
 */

/** A single big circular arm button, as on the Home security hero (well above the 48dp floor). */
@Composable
private fun ArmCircle(icon: ImageVector, label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = {},
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A flat camera-snapshot placeholder (no Coil/network in the audit render). */
@Composable
private fun CameraPlaceholder(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/**
 * Home — the camera-forward security hero: three arm circles, the plain-language security read-out,
 * a 2-up camera grid, and a rooms entry. Mirrors `ui/home/HomeScreen.kt`.
 */
@Composable
fun SecurityHeroScene() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hawksnest", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                ConnectionPill(status = ConnectionStatus.CONNECTED)
            }
            // Big alarm read-out (real label from Alarm.kt — note the em dash).
            Text("Armed — Away", style = MaterialTheme.typography.displayMedium)
            Text(
                "All doors locked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ArmCircle(Icons.Filled.LockOpen, "Disarm", active = false)
                ArmCircle(Icons.Filled.Home, "Home", active = false)
                ArmCircle(Icons.Filled.Lock, "Away", active = true)
            }
            SectionHeader(title = "Cameras")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraPlaceholder("Front Door", Modifier.weight(1f))
                CameraPlaceholder("Backyard", Modifier.weight(1f))
            }
            PanelCard(onClick = {}) {
                Text("Rooms", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Living Room · Kitchen · Garage · Office",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Area detail — device controls and sensor stats: the kind of mixed-density content Sift's contrast,
 * touch-target, and type-hierarchy rules care about. Mirrors `ui/area/AreaDetailScreen.kt`.
 */
@Composable
fun ControlsScene() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(title = "Living Room")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(label = "Temperature", value = "71°", modifier = Modifier.weight(1f))
                StatTile(label = "Humidity", value = "44%", modifier = Modifier.weight(1f))
            }
            PanelCard {
                Text("Ceiling Light", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                DataText(text = "On · 80%")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {}) { Text("Toggle") }
                    OutlinedButton(onClick = {}) { Text("Details") }
                }
            }
            PanelCard {
                Text("Front Door", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                DataText(text = "Locked")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {}) { Text("Unlock") }
            }
        }
    }
}

/**
 * Settings — the HA URL + token form and connection status. Mirrors `ui/settings/SettingsScreen.kt`.
 */
@Composable
fun SettingsScene() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            ConnectionPill(status = ConnectionStatus.ERROR)
            Text(
                "Connect Hawksnest to your Home Assistant over Tailscale.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = "http://hawksnest.tailnet.ts.net:8080",
                onValueChange = {},
                label = { Text("Home Assistant URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = "",
                onValueChange = {},
                label = { Text("Long-lived access token") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
    }
}
