package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.numberAttr
import com.hawksnest.core.ha.stringListAttr
import com.hawksnest.core.logic.DoorbellControls
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.delay

/**
 * The doorbell's own settings: chime volume, whether the button makes a sound at the door, the
 * message played automatically to a visitor, and the siren.
 *
 * Everything renders only when its entity exists, so this panel serves whatever the model actually
 * exposes without asking which model it is — the same discipline as [PtzPanel]. Only doorbells
 * resolve controls at all (`DoorbellControls.kt`), so a wall camera never shows this.
 *
 * **The siren here is the `siren` DOMAIN**, not ring-mqtt's `switch.<base>_siren`. `control()`
 * derives the service domain from the entity id, so the same call reaches `siren.turn_on`.
 *
 * Twin of `src/components/camera/DoorbellPanel.tsx`.
 */
@Composable
fun DoorbellPanel(
    controls: DoorbellControls,
    viewModel: CameraPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("doorbellPanel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VolumeSlider(controls.volume, viewModel)
        controls.buttonSound?.let { ToggleRow(it, "Button sound", viewModel) }
        controls.autoReply?.let { OptionRow(it, "Auto reply", viewModel) }
        controls.playReply?.let { OptionRow(it, "Play message", viewModel) }
        controls.siren?.let { SirenRow(it, viewModel) }
    }
}

/**
 * Chime volume as a commit-on-release slider — same interaction as [PtzPanel]'s sliders. The thumb
 * follows the drag locally and one service call fires on release, so dragging the range does not
 * put twenty commands on the camera's control session.
 */
@Composable
private fun VolumeSlider(entityId: String, viewModel: CameraPlayerViewModel) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val min = entity?.numberAttr("min") ?: 0.0
    val max = entity?.numberAttr("max") ?: 100.0
    val remote = entity?.state?.toDoubleOrNull()

    // Null while not dragging: the slider then follows the camera, so a change made in the
    // Reolink app is reflected here.
    var local by remember(entityId) { mutableStateOf<Float?>(null) }
    val value = local ?: remote?.toFloat() ?: min.toFloat()

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Chime volume",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value.toInt().toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { local = it },
            onValueChangeFinished = {
                local?.let { viewModel.setNumber(entityId, it.toDouble(), "Chime volume") }
                local = null
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = entity != null,
        )
    }
}

/** A `switch` entity as a labelled toggle. */
@Composable
private fun ToggleRow(entityId: String, label: String, viewModel: CameraPlayerViewModel) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val on = entity?.state == "on"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = on,
            onCheckedChange = { viewModel.setToggle(entityId, it, label) },
            enabled = entity != null,
        )
    }
}

/** A `select` entity. The options come from the camera itself. */
@Composable
private fun OptionRow(entityId: String, label: String, viewModel: CameraPlayerViewModel) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val options = entity?.stringListAttr("options").orEmpty()
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.take(4).forEach { option ->
                val selected = entity?.state == option
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable { viewModel.selectOption(entityId, option, label) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The Reolink siren.
 *
 * Two taps to turn ON, one to turn OFF — the same asymmetry the web `SirenButton` uses, and for
 * the same reason: the siren is loud, so firing it should take deliberate intent while silencing
 * it is always the fast path.
 */
@Composable
private fun SirenRow(entityId: String, viewModel: CameraPlayerViewModel) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val on = entity?.state == "on"
    var armed by remember(entityId) { mutableStateOf(false) }

    // Drop the armed state if the user doesn't confirm in time.
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }

    val pulse = HawksnestTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (on || armed) pulse.streakDim else MaterialTheme.colorScheme.surface)
            .clickable {
                when {
                    on -> {
                        viewModel.setToggle(entityId, false, "Siren")
                        armed = false
                    }
                    !armed -> armed = true
                    else -> {
                        armed = false
                        viewModel.setToggle(entityId, true, "Siren")
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("doorbellSiren"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Siren",
            style = MaterialTheme.typography.labelMedium,
            color = if (on || armed) pulse.streak else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (on) "Silence" else if (armed) "Confirm" else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (on || armed) pulse.streak else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
