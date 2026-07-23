package com.hawksnest.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.dimCommit
import com.hawksnest.core.logic.dragToPct
import com.hawksnest.core.logic.tickCrossed
import com.hawksnest.core.logic.washAlpha
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion

/** Default warmth for lights that report no color info — a comfortable neutral-warm. */
private const val NEUTRAL_WARMTH = 0.35f

/**
 * The light pillar — the whole surface *is* the dimmer. A glow fill rises from the bottom to the
 * brightness level, tinted by the light's own warmth (cool blue-white → warm amber); drag
 * anywhere vertically to dim with a haptic tick at each quarter, tap to toggle. The percent
 * readout tracks the finger live but HA hears exactly one call per gesture, on release
 * ([dimCommit] — the floor commits a real `turn_off`).
 *
 * Optimistic like every light control here: the fill follows the finger and the tap immediately,
 * HA's echo reconciles, and a failed call snaps back via [rememberOptimisticOnOff].
 *
 * Deliberate tradeoff: the pillar consumes vertical drags over its own bounds (that is what
 * "the widget is the control" means). It renders on the Area/Entity detail screens, not in the
 * long Devices list, so the page still scrolls from anywhere else.
 */
@Composable
fun LightPillar(
    on: Boolean,
    dimmable: Boolean,
    pct: Int,
    warmth: Float?,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onCommitPct: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 148.dp,
    testTag: String? = null,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val (shownOn, setOnTarget) = rememberOptimisticOnOff(on, pending)
    // The pointerInput lambdas outlive recompositions — read changing values through
    // rememberUpdatedState or they act on the composition they were launched in.
    val currentShownOn by rememberUpdatedState(shownOn)
    val currentPct by rememberUpdatedState(pct)
    val currentSetOnTarget by rememberUpdatedState(setOnTarget)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnCommitPct by rememberUpdatedState(onCommitPct)

    // Live level: local while dragging, resynced to HA's echo when [pct] changes.
    var livePct by remember(pct) { mutableIntStateOf(pct) }
    var dragging by remember { mutableStateOf(false) }
    var dragStartPct by remember { mutableIntStateOf(0) }
    var dragDelta by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0f) }

    val shownPct = if (shownOn || dragging) livePct else 0
    val fill by animateFloatAsState(
        targetValue = if (dimmable) shownPct / 100f else if (shownOn) 1f else 0f,
        animationSpec = if (dragging) snap() else PulseMotion.standard(),
        label = "pillarFill",
    )
    val warm = lerp(pulse.effort, pulse.streak, warmth ?: NEUTRAL_WARMTH)
    val wash = washAlpha(on = shownOn || dragging, dimmable = dimmable, pct = shownPct)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, if (shownOn) warm.copy(alpha = 0.35f) else pulse.hairline, MaterialTheme.shapes.medium)
            .drawBehind {
                drawRect(pulse.panelHigh)
                if (fill > 0f) {
                    val top = size.height * (1f - fill)
                    val fillSize = Size(size.width, size.height - top)
                    // The rising glow: a strength base under the light's own warmth.
                    drawRect(pulse.strengthDim, topLeft = Offset(0f, top), size = fillSize)
                    drawRect(warm.copy(alpha = wash), topLeft = Offset(0f, top), size = fillSize)
                    // Waterline marking the level.
                    drawRect(
                        warm.copy(alpha = 0.6f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, 2.dp.toPx()),
                    )
                }
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = {
                    val next = !currentShownOn
                    if (next) haptics.toggleOn() else haptics.toggleOff()
                    currentSetOnTarget(next)
                    currentOnToggle(next)
                })
            }
            .pointerInput(enabled, dimmable) {
                if (!enabled || !dimmable) return@pointerInput
                heightPx = size.height.toFloat()
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragStartPct = if (currentShownOn) livePct else 0
                        dragDelta = 0f
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        dragDelta += delta
                        val next = dragToPct(dragStartPct, dragDelta, heightPx)
                        tickCrossed(livePct, next)?.let { haptics.threshold() }
                        livePct = next
                    },
                    onDragCancel = {
                        dragging = false
                        livePct = currentPct
                    },
                    onDragEnd = {
                        dragging = false
                        haptics.confirm()
                        currentSetOnTarget(livePct > 0)
                        currentOnCommitPct(livePct)
                    },
                )
            },
    ) {
        Icon(
            Icons.Filled.Lightbulb,
            contentDescription = null,
            tint = if (shownOn) warm else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(HawksnestTheme.spacing.md)
                .size(20.dp),
        )
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(HawksnestTheme.spacing.md)
                    .size(16.dp),
                color = pulse.effort,
                strokeWidth = 2.dp,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(HawksnestTheme.spacing.md),
        ) {
            if (dimmable && (shownOn || dragging)) {
                DataText(
                    text = "$shownPct%",
                    style = HawksnestTheme.dataType.dataMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = if (shownOn) "On" else "Off",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
