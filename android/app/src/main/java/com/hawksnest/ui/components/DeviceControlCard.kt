package com.hawksnest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.CardType
import com.hawksnest.core.logic.lockStateLabel
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** A controllable entity for list/detail rendering. */
data class DeviceUi(
    val entityId: String,
    val name: String,
    val stateText: String,
    val rawState: String,
    val card: CardType,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private val SAFETY_CLASSES = setOf("safety", "tamper", "problem", "smoke", "gas", "carbon_monoxide", "moisture")
private val DOOR_CLASSES = setOf("door", "window", "opening", "garage_door", "contact")

/**
 * One device row with its controls — full domain coverage (lock, switch, light + brightness, fan +
 * speed, cover, climate, media, alarm; binary_sensor + generic read-only). Non-optimistic: callers
 * route [onCall] (service + extra service-data) through `ConnectionManager.callService`; the store
 * reconciles from HA's echo. Shared by the Devices tab and per-room Area detail.
 */
@Composable
fun DeviceControlCard(
    device: DeviceUi,
    onCall: (service: String, extra: Map<String, Any?>) -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val pulse = HawksnestTheme.pulse
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpen != null) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when (device.card) {
            CardType.LOCK -> ControlRow {
                PulseButton(
                    text = "Lock", onClick = { onCall("lock", emptyMap()) },
                    modifier = Modifier.weight(1f), tonal = true, compact = true,
                    channel = pulse.recovery, onChannel = pulse.onRecovery, dimChannel = pulse.recoveryDim,
                )
                PulseButton(
                    text = "Unlock",
                    onClick = { onCall("unlock", emptyMap()) },
                    modifier = Modifier.weight(1f), tonal = true, compact = true,
                    channel = pulse.streak, onChannel = pulse.onStreak, dimChannel = pulse.streakDim,
                )
            }
            CardType.SWITCH -> ToggleRow(device.rawState == "on", onCall)
            CardType.LIGHT -> {
                val on = device.rawState == "on"
                ToggleRow(on, onCall)
                val pct = device.attributes.num("brightness")?.let { (it / 2.55).roundToInt() } ?: 0
                LevelSlider(pct, enabled = on) { v -> onCall("turn_on", mapOf("brightness_pct" to v)) }
            }
            CardType.FAN -> {
                val on = device.rawState == "on"
                ToggleRow(on, onCall)
                val pct = device.attributes.num("percentage")?.roundToInt() ?: 0
                LevelSlider(pct, enabled = on) { v -> onCall("set_percentage", mapOf("percentage" to v)) }
            }
            CardType.COVER -> ControlRow {
                CoverButton("Open", pulse.streak) { onCall("open_cover", emptyMap()) }
                CoverButton("Stop", null) { onCall("stop_cover", emptyMap()) }
                CoverButton("Close", pulse.recovery) { onCall("close_cover", emptyMap()) }
            }
            CardType.CLIMATE -> {
                val target = device.attributes.num("temperature")
                val step = device.attributes.num("target_temp_step") ?: 0.5
                val unit = device.attributes.str("unit_of_measurement") ?: "°"
                Box(Modifier.padding(top = HawksnestTheme.spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                        IconButton(onClick = { target?.let { onCall("set_temperature", mapOf("temperature" to it - step)) } }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Cooler", tint = pulse.effort)
                        }
                        Text(
                            if (target != null) "${fmtTemp(target)}$unit" else "—",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { target?.let { onCall("set_temperature", mapOf("temperature" to it + step)) } }) {
                            Icon(Icons.Filled.Add, contentDescription = "Warmer", tint = pulse.effort)
                        }
                    }
                }
            }
            CardType.MEDIA_PLAYER -> ControlRow {
                IconButton(onClick = { onCall("media_previous_track", emptyMap()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { onCall("media_play_pause", emptyMap()) }, modifier = Modifier.weight(1f)) {
                    val playing = device.rawState == "playing"
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause", tint = pulse.effort)
                }
                IconButton(onClick = { onCall("media_next_track", emptyMap()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            CardType.ALARM -> ControlRow {
                ARM_BUTTONS.forEach { b ->
                    ArmSegment(
                        label = b.label,
                        active = device.rawState == b.state,
                        onClick = { onCall(b.service, emptyMap()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> Unit // BINARY_SENSOR / CAMERA / GENERIC are read-only (subtitle carries the state)
        }
    }
}

/** Human-readable state line per domain. */
private fun subtitle(d: DeviceUi): String = when (d.card) {
    CardType.LIGHT -> if (d.rawState == "on") "On · ${d.attributes.num("brightness")?.let { (it / 2.55).roundToInt() } ?: 0}%" else "Off"
    CardType.FAN -> if (d.rawState == "on") "On · ${d.attributes.num("percentage")?.roundToInt() ?: 0}%" else "Off"
    CardType.COVER -> "${d.stateText} · ${d.attributes.num("current_position")?.roundToInt() ?: 0}%"
    CardType.CLIMATE -> {
        val cur = d.attributes.num("current_temperature")
        val unit = d.attributes.str("unit_of_measurement") ?: "°"
        if (cur != null) "${d.stateText} · now ${fmtTemp(cur)}$unit" else d.stateText
    }
    CardType.MEDIA_PLAYER -> {
        val t = d.attributes.str("media_title")
        val a = d.attributes.str("media_artist")
        when {
            t != null && a != null -> "$t — $a"
            t != null -> t
            else -> d.stateText
        }
    }
    CardType.LOCK -> lockStateLabel(d.rawState)
    CardType.BINARY_SENSOR -> binarySensorText(d.rawState, d.attributes.str("device_class"))
    CardType.GENERIC -> d.attributes.str("unit_of_measurement")?.let { "${d.stateText} $it" } ?: d.stateText
    else -> d.stateText
}

private fun binarySensorText(state: String, deviceClass: String?): String {
    val on = state == "on"
    return when (deviceClass) {
        in SAFETY_CLASSES -> if (on) "Detected" else "Safe"
        in DOOR_CLASSES -> if (on) "Open" else "Closed"
        else -> if (on) "On" else "Off"
    }
}

private fun fmtTemp(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)

@Composable
private fun ToggleRow(on: Boolean, onCall: (String, Map<String, Any?>) -> Unit) {
    val pulse = HawksnestTheme.pulse
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (on) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = { onCall(if (it) "turn_on" else "turn_off", emptyMap()) },
            colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
        )
    }
}

@Composable
private fun LevelSlider(pct: Int, enabled: Boolean, onSet: (Int) -> Unit) {
    var pos by remember(pct) { mutableFloatStateOf(pct.toFloat()) }
    Slider(
        value = pos,
        onValueChange = { pos = it },
        onValueChangeFinished = { onSet(pos.roundToInt().coerceIn(1, 100)) },
        valueRange = 1f..100f,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RowScope.CoverButton(label: String, channel: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.small)
            .background(pulse.panelHigh)
            .clickable(onClick = onClick)
            .padding(vertical = HawksnestTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = channel ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.padding(top = HawksnestTheme.spacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm), content = content)
    }
}

@Composable
private fun ArmSegment(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) pulse.effortDim else pulse.panelHigh)
            .clickable(onClick = onClick)
            .padding(vertical = HawksnestTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
