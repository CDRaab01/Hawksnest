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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.CardType
import com.hawksnest.core.logic.isDimmableLight
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
 * speed, cover, climate, media, alarm; binary_sensor + generic read-only). Callers route [onCall]
 * through `ConnectionManager.control` (crash-safe, pending-tracked) and feed [pending] back from
 * `pendingControls`. Security domains (lock, alarm) stay **non-optimistic** — they show an honest
 * pending state until HA echoes; lights/switches/fans render **optimistically** (the thumb follows
 * the finger, the echo reconciles, a failure snaps back). Shared by the Devices tab and per-room
 * Area detail.
 */
@Composable
fun DeviceControlCard(
    device: DeviceUi,
    onCall: (service: String, extra: Map<String, Any?>) -> Unit,
    onOpen: (() -> Unit)? = null,
    pending: Boolean = false,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
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
            // Locks are the deliberate-action exception: no tap target at all — a slide commits,
            // and the track holds an honest pending state until HA echoes (never optimistic).
            CardType.LOCK -> {
                val locked = device.rawState == "locked"
                val transitional = device.rawState == "locking" || device.rawState == "unlocking"
                // The bolt's confirm tick: when a busy lock settles into locked/unlocked
                // (HA's echo — the moment the physical bolt actually threw), buzz once.
                var wasBusy by remember { mutableStateOf(false) }
                LaunchedEffect(device.rawState, pending) {
                    val settledNow = device.rawState == "locked" || device.rawState == "unlocked"
                    if (wasBusy && settledNow && !pending) haptics.confirm()
                    wasBusy = pending || transitional
                }
                Box(Modifier.padding(top = HawksnestTheme.spacing.md)) {
                    SlideToAct(
                        label = if (locked) "Slide to unlock" else "Slide to lock",
                        pendingLabel = when {
                            device.rawState == "locking" -> "Locking…"
                            device.rawState == "unlocking" -> "Unlocking…"
                            locked -> "Unlocking…" // gate-pending before the first echo
                            else -> "Locking…"
                        },
                        icon = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        channel = if (locked) pulse.streak else pulse.recovery,
                        onChannel = if (locked) pulse.onStreak else pulse.onRecovery,
                        dimChannel = if (locked) pulse.streakDim else pulse.recoveryDim,
                        pending = pending || transitional,
                        enabled = device.rawState != "unavailable",
                        onCommit = { onCall(if (locked) "unlock" else "lock", emptyMap()) },
                        testTag = "slide-${device.entityId}",
                    )
                }
            }
            CardType.SWITCH -> ToggleRow(device.rawState == "on", pending, haptics, onCall)
            CardType.LIGHT -> {
                val on = device.rawState == "on"
                ToggleRow(on, pending, haptics, onCall)
                // On/off-only lights (relay/switch-type) get no dimmer — a dead
                // slider on every porch light was worse than no slider.
                if (isDimmableLight(device.attributes)) {
                    val pct = device.attributes.num("brightness")?.let { (it / 2.55).roundToInt() } ?: 0
                    LevelSlider(pct, enabled = on) { v -> onCall("turn_on", mapOf("brightness_pct" to v)) }
                }
            }
            CardType.FAN -> {
                val on = device.rawState == "on"
                ToggleRow(on, pending, haptics, onCall)
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
            CardType.ALARM -> {
                // Which segment was tapped, so its spinner (not all three) shows while HA works
                // through arming/exit delay. Cleared once the panel settles.
                var tapped by remember { mutableStateOf<String?>(null) }
                val transitional = device.rawState in ALARM_TRANSITIONAL
                LaunchedEffect(pending, transitional) { if (!pending && !transitional) tapped = null }
                ControlRow {
                    ARM_BUTTONS.forEach { b ->
                        ArmSegment(
                            label = b.label,
                            active = device.rawState == b.state,
                            busy = (pending || transitional) && tapped == b.service,
                            enabled = !pending,
                            onClick = {
                                haptics.toggleOn()
                                tapped = b.service
                                onCall(b.service, emptyMap())
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            else -> Unit // BINARY_SENSOR / CAMERA / GENERIC are read-only (subtitle carries the state)
        }
    }
}

/** Human-readable state line per domain. */
private fun subtitle(d: DeviceUi): String = when (d.card) {
    CardType.LIGHT ->
        if (d.rawState != "on") "Off"
        else if (isDimmableLight(d.attributes)) "On · ${d.attributes.num("brightness")?.let { (it / 2.55).roundToInt() } ?: 0}%"
        else "On"
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

/**
 * Optimistic switch for non-security domains: the thumb follows the finger *immediately* (a
 * non-optimistic switch snaps back under the finger and feels broken), then HA's echo becomes the
 * authoritative state. If the call fails or times out, [pending] clears without an echo and the
 * thumb snaps back — the app-level snackbar carries the error.
 */
@Composable
private fun ToggleRow(
    on: Boolean,
    pending: Boolean,
    haptics: Haptics,
    onCall: (String, Map<String, Any?>) -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val (shown, setTarget) = rememberOptimisticOnOff(on, pending)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (shown) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = shown,
            onCheckedChange = {
                if (it) haptics.toggleOn() else haptics.toggleOff()
                setTarget(it)
                onCall(if (it) "turn_on" else "turn_off", emptyMap())
            },
            colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
        )
    }
}

/**
 * Hoisted optimistic on/off state: the shown value follows the user's tap
 * immediately, HA's echo (via `remember(on)`) reconciles it, and a cleared
 * pending without an echo snaps back. Shared by the device cards' ToggleRow
 * and the Devices list's compact rows.
 */
@Composable
fun rememberOptimisticOnOff(on: Boolean, pending: Boolean): Pair<Boolean, (Boolean) -> Unit> {
    var target by remember(on) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pending) { if (!pending) target = null }
    return (target ?: on) to { target = it }
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

/** HA alarm-panel states where a command is still settling (exit delays, entry countdowns). */
private val ALARM_TRANSITIONAL = setOf("arming", "disarming", "pending")

@Composable
private fun ArmSegment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) pulse.effortDim else pulse.panelHigh)
            .clickable(onClick = onClick, enabled = enabled && !busy)
            .padding(vertical = HawksnestTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = pulse.effort,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
