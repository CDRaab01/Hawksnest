package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.numberAttr
import com.hawksnest.core.ha.stringListAttr
import com.hawksnest.core.logic.PtzControls
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The camera-control drawer: movement pad, optical zoom, focus and saved
 * positions — the Reolink app's control surface, minus what this hardware or this
 * app deliberately doesn't do (no speed slider, no patrol, no digital zoom, no
 * auto-tracking; see `core/logic/CameraPtz.kt` and the audit plan).
 *
 * Every control renders only when its entity exists, so the same panel serves an
 * E1 Zoom (pad + zoom + focus) and an E1 Pro (pad only) without asking what model
 * it is. Twin of the web `PtzPanel.tsx` — keep in lockstep.
 */
@Composable
fun PtzPanel(
    ptz: PtzControls,
    viewModel: CameraPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PtzPad(ptz, viewModel)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ptz.zoom?.let { NumberSlider(it, "Zoom", viewModel) }
            ptz.focus?.let { focusId -> FocusRow(focusId, ptz.autofocus, viewModel) }
            ptz.preset?.let { PresetRow(it, viewModel) }
        }
    }
}

/**
 * Press and hold a direction to move, release to stop.
 *
 * **Hold-to-move works whichever way the camera behaves.** Whether a Reolink
 * direction press starts a continuous move (until `ptz_stop`) or advances one step
 * is not settled — testing it physically re-aims a recording camera, so it is an
 * on-device smoke-test item. Press-on-down / stop-on-release is correct under both
 * readings. No speed control, deliberately: the cameras report
 * `supportPtzSpeed: {permit: 0}`, so a speed slider would be a lie.
 *
 * The safety property is that **the camera must never be left moving**:
 * `tryAwaitRelease` covers release AND gesture cancellation, and the pad also
 * stops on leaving composition and on the app being backgrounded.
 */
@Composable
private fun PtzPad(
    ptz: PtzControls,
    viewModel: CameraPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    // Which direction is in flight, so a second press can't stack a second move
    // and so teardown knows whether a stop is owed.
    val moving = remember(ptz.slug) { mutableStateOf<String?>(null) }
    val current = rememberUpdatedState(ptz)

    fun stop() {
        if (moving.value == null) return
        moving.value = null
        viewModel.stopPtz(current.value.stop)
    }

    fun start(entityId: String) {
        if (moving.value != null) return
        moving.value = entityId
        viewModel.pressPtz(entityId)
    }

    // Leaving the player (camera switch, scrub to recorded, closing the lightbox)
    // must not leave the lens panning — nor may backgrounding the app, which never
    // delivers the release event.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, ptz.slug) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }

    Column(
        modifier.width(132.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PadButton(Icons.Filled.KeyboardArrowUp, "Pan up", { start(ptz.up) }, ::stop)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadButton(Icons.Filled.KeyboardArrowLeft, "Pan left", { start(ptz.left) }, ::stop)
            // The manual escape hatch: always sends, even with nothing tracked as
            // moving, in case a move was ever left running.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        moving.value = null
                        viewModel.stopPtz(ptz.stop)
                    }
                    .semantics { contentDescription = "Stop camera movement" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            PadButton(Icons.Filled.KeyboardArrowRight, "Pan right", { start(ptz.right) }, ::stop)
        }
        PadButton(Icons.Filled.KeyboardArrowDown, "Pan down", { start(ptz.down) }, ::stop)
    }
}

@Composable
private fun PadButton(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val press = rememberUpdatedState(onPress)
    val release = rememberUpdatedState(onRelease)
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (held) HawksnestTheme.pulse.effort else MaterialTheme.colorScheme.surface,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        held = true
                        press.value()
                        // Returns on release OR cancellation — both must stop the camera.
                        tryAwaitRelease()
                        held = false
                        release.value()
                    },
                )
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A `number` entity as a commit-on-release slider. The thumb follows the drag
 * locally and the service call fires once on release, so dragging across the range
 * doesn't put twenty commands on the camera's control session.
 */
@Composable
private fun NumberSlider(
    entityId: String,
    label: String,
    viewModel: CameraPlayerViewModel,
    enabled: Boolean = true,
) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val min = entity?.numberAttr("min") ?: 0.0
    val max = entity?.numberAttr("max") ?: 100.0
    val remote = entity?.state?.toDoubleOrNull()

    // Null while not dragging: the slider then follows the camera, so a preset
    // recall or the Reolink app moving the lens is reflected here.
    var local by remember(entityId) { mutableStateOf<Float?>(null) }
    val value = local ?: remote?.toFloat() ?: min.toFloat()

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
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
                local?.let { viewModel.setNumber(entityId, it.toDouble(), label) }
                local = null
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled && entity != null,
        )
    }
}

/**
 * Focus, with its autofocus switch. The manual slider is disabled while autofocus
 * is on — the camera would immediately override anything set, so offering it would
 * be a control that visibly does nothing.
 */
@Composable
private fun FocusRow(
    focusId: String,
    autofocusId: String?,
    viewModel: CameraPlayerViewModel,
) {
    val auto: HassEntity? by remember(autofocusId) {
        viewModel.entityFlow(autofocusId ?: "")
    }.collectAsState(initial = null)
    val isAuto = auto?.state == "on"

    Column {
        if (autofocusId != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Autofocus",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = isAuto,
                    onCheckedChange = { viewModel.setAutofocus(autofocusId, it) },
                    modifier = Modifier.semantics { contentDescription = "Autofocus" },
                )
            }
        }
        NumberSlider(focusId, "Focus", viewModel, enabled = !isAuto)
    }
}

/** Saved camera positions. The options come from the camera itself. */
@Composable
private fun PresetRow(entityId: String, viewModel: CameraPlayerViewModel) {
    val entity: HassEntity? by remember(entityId) { viewModel.entityFlow(entityId) }
        .collectAsState(initial = null)
    val options = entity?.stringListAttr("options").orEmpty()
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Position",
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
                        .clickable { viewModel.selectPtzPreset(entityId, option) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
