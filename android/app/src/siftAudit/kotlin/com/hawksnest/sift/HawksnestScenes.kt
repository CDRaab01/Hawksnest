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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.hawksnest.core.logic.CardType
import com.hawksnest.core.logic.lockVaultView
import com.hawksnest.core.logic.thermostatView
import com.hawksnest.ui.components.ArmSegments
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.components.LightPillar
import com.hawksnest.ui.components.LockVault
import com.hawksnest.ui.components.MediaTransport
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.RockerSwitch
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.StatTile
import com.hawksnest.ui.components.ThermostatDial
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * touch-target, and type-hierarchy rules care about. Mirrors `ui/area/AreaDetailScreen.kt`, and
 * renders the **real** `DeviceControlCard` so the audit sees the widgets users actually touch —
 * the light pillar and the lock vault.
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
            DeviceControlCard(
                device = DeviceUi(
                    entityId = "light.ceiling",
                    name = "Ceiling Light",
                    stateText = "on",
                    rawState = "on",
                    card = CardType.LIGHT,
                    attributes = JsonObject(
                        mapOf(
                            "brightness" to JsonPrimitive(204),
                            "supported_color_modes" to JsonArray(listOf(JsonPrimitive("color_temp"))),
                            "color_temp_kelvin" to JsonPrimitive(3000),
                        ),
                    ),
                ),
                onCall = { _, _ -> },
            )
            DeviceControlCard(
                device = DeviceUi(
                    entityId = "lock.front_door",
                    name = "Front Door",
                    stateText = "locked",
                    rawState = "locked",
                    card = CardType.LOCK,
                    attributes = JsonObject(emptyMap()),
                ),
                onCall = { _, _ -> },
            )
        }
    }
}

/**
 * The premium control widgets, one of each in a deterministic state: the dimmer pillar warm at
 * 62%, the rocker on and off, the vault locked and jammed, the thermostat dial mid-heat, the arm
 * segments and the media transport. Static props only — no pending spinners or infinite
 * animations — so the Robolectric render is stable for the token/contrast/touch-target rules.
 */
@Composable
fun WidgetsScene() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(title = "Controls")
            LightPillar(
                on = true,
                dimmable = true,
                pct = 62,
                warmth = 0.7f,
                pending = false,
                onToggle = {},
                onCommitPct = {},
            )
            RockerSwitch(on = true, pending = false, onToggle = {})
            RockerSwitch(on = false, pending = false, onToggle = {})
            LockVault(view = lockVaultView("locked"), pending = false, onCommit = {})
            LockVault(view = lockVaultView("jammed"), pending = false, onCommit = {})
            ThermostatDial(
                view = thermostatView(
                    JsonObject(
                        mapOf(
                            "temperature" to JsonPrimitive(72.0),
                            "current_temperature" to JsonPrimitive(68.0),
                            "min_temp" to JsonPrimitive(45.0),
                            "max_temp" to JsonPrimitive(95.0),
                            "target_temp_step" to JsonPrimitive(1.0),
                            "unit_of_measurement" to JsonPrimitive("°F"),
                            "hvac_action" to JsonPrimitive("heating"),
                        ),
                    ),
                    "heat",
                ),
                pending = false,
                onCommitTemp = {},
            )
            ArmSegments(rawState = "armed_home", pending = false, onArm = {})
            MediaTransport(playing = true, onPrev = {}, onPlayPause = {}, onNext = {})
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
            PulseButton(text = "Connect", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}
