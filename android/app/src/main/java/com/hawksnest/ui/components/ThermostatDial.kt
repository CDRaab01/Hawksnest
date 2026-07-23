package com.hawksnest.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.DIAL_START_DEG
import com.hawksnest.core.logic.DIAL_SWEEP_DEG
import com.hawksnest.core.logic.ThermostatView
import com.hawksnest.core.logic.fmtTemp
import com.hawksnest.core.logic.fractionToTemp
import com.hawksnest.core.logic.tempToFraction
import com.hawksnest.core.logic.touchToFraction
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.color
import kotlin.math.hypot

/** How far either side of the arc's centerline a touch still counts as grabbing the arc. */
private val ArcGrabBand = 28.dp
private val ArcStroke = 10.dp

/**
 * The thermostat dial: a 270° arc you drag the setpoint along, in the [ProgressRing] idiom —
 * hairline track, round-capped channel arc, soft glow. The arc wears what the HVAC is doing
 * (streak while heating, effort while cooling, neutral at idle); the center is the setpoint in
 * mono type with the measured "now" beneath it. The flanking −/+ buttons stay as precise,
 * accessible step controls — the arc is the hero, not the only door.
 *
 * One `set_temperature` per gesture: the readout tracks the finger live (with a tick per snapped
 * step), the commit fires once on release. Not a security surface, so the display is optimistic
 * and HA's echo reconciles. A read-only view ([ThermostatView.adjustable] false) draws the dial
 * without gestures or steppers. Touches in the bottom dead gap never move the setpoint.
 */
@Composable
fun ThermostatDial(
    view: ThermostatView,
    pending: Boolean,
    onCommitTemp: (Double) -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 200.dp,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val channelColor = view.channel?.let { pulse.color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant

    var liveTarget by remember(view.target) { mutableStateOf(view.target) }
    var dragging by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    val currentOnCommit by rememberUpdatedState(onCommitTemp)

    val fraction = liveTarget?.let { tempToFraction(it, view.min, view.max) } ?: 0f
    val sweep by animateFloatAsState(
        targetValue = fraction * DIAL_SWEEP_DEG,
        animationSpec = if (dragging) snap() else PulseMotion.data(),
        label = "dialSweep",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (view.adjustable) {
            IconButton(
                onClick = {
                    liveTarget?.let {
                        val next = (it - view.step).coerceIn(view.min, view.max)
                        liveTarget = next
                        haptics.threshold()
                        onCommitTemp(next)
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Cooler", tint = pulse.effort)
            }
        }
        Box(
            modifier = Modifier
                .size(dialSize)
                .pointerInput(view.adjustable) {
                    if (!currentView.adjustable) return@pointerInput
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = minOf(size.width, size.height) / 2f - ArcStroke.toPx() * 1.5f
                    val band = ArcGrabBand.toPx()
                    var active = false

                    // Snap the setpoint to the touched arc position; dead-gap touches do nothing.
                    fun moveTo(pos: Offset) {
                        val v = currentView
                        val f = touchToFraction(pos.x, pos.y, cx, cy) ?: return
                        val next = fractionToTemp(f, v.min, v.max, v.step)
                        if (next != liveTarget) haptics.threshold()
                        liveTarget = next
                    }

                    detectDragGestures(
                        onDragStart = { pos ->
                            val dist = hypot(pos.x - cx, pos.y - cy)
                            active = dist in (radius - band)..(radius + band) &&
                                touchToFraction(pos.x, pos.y, cx, cy) != null
                            if (active) {
                                dragging = true
                                moveTo(pos)
                            }
                        },
                        onDrag = { change, _ ->
                            if (active) {
                                change.consume()
                                moveTo(change.position)
                            }
                        },
                        onDragCancel = {
                            if (active) {
                                active = false
                                dragging = false
                                liveTarget = currentView.target
                            }
                        },
                        onDragEnd = {
                            if (active) {
                                active = false
                                dragging = false
                                liveTarget?.let {
                                    haptics.confirm()
                                    currentOnCommit(it)
                                }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val strokePx = ArcStroke.toPx()
                val inset = strokePx * 1.5f
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val arcAlpha = if (pending) 0.55f else 1f
                drawArc(
                    color = channelColor.copy(alpha = 0.12f),
                    startAngle = DIAL_START_DEG,
                    sweepAngle = DIAL_SWEEP_DEG,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                if (sweep > 0f && liveTarget != null) {
                    drawArc(
                        color = channelColor.copy(alpha = 0.18f * arcAlpha),
                        startAngle = DIAL_START_DEG,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = channelColor.copy(alpha = arcAlpha),
                        startAngle = DIAL_START_DEG,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
                // The measured room temperature, marked on the track.
                view.current?.let { cur ->
                    val f = tempToFraction(cur, view.min, view.max)
                    val angleRad = Math.toRadians((DIAL_START_DEG + f * DIAL_SWEEP_DEG).toDouble())
                    val r = arcSize.width / 2f
                    val center = Offset(topLeft.x + r, topLeft.y + arcSize.height / 2f)
                    drawCircle(
                        color = pulse.hairlineStrong,
                        radius = strokePx * 0.45f,
                        center = center + Offset(
                            (r * Math.cos(angleRad)).toFloat(),
                            (r * Math.sin(angleRad)).toFloat(),
                        ),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val t = liveTarget
                if (t != null) {
                    Row(verticalAlignment = Alignment.Top) {
                        DataText(
                            text = fmtTemp(t),
                            style = HawksnestTheme.dataType.dataLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            view.unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("—", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                view.current?.let {
                    DataText(
                        text = "now ${fmtTemp(it)}${view.unit}",
                        style = HawksnestTheme.dataType.numeral,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (view.adjustable) {
            IconButton(
                onClick = {
                    liveTarget?.let {
                        val next = (it + view.step).coerceIn(view.min, view.max)
                        liveTarget = next
                        haptics.threshold()
                        onCommitTemp(next)
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Warmer", tint = pulse.effort)
            }
        }
    }
}
