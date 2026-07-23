package com.hawksnest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion
import kotlin.math.roundToInt

private val TrackHeight = 48.dp
private val ThumbSize = 36.dp
private val TrackPadding = 6.dp

/**
 * The premium on/off surface: a full-width rocker whose thumb springs across the track, the
 * track itself charging with the effort channel as it lands. Tap anywhere to flip, or drag the
 * thumb and release past the midpoint — either way it's one `turn_on`/`turn_off` call.
 *
 * **Optimistic** like every non-security control here (via [rememberOptimisticOnOff]): the thumb
 * follows the finger immediately, HA's echo reconciles, and a failed call snaps it back — the
 * app-level snackbar carries the error.
 */
@Composable
fun RockerSwitch(
    on: Boolean,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val (shown, setTarget) = rememberOptimisticOnOff(on, pending)
    val currentShown by rememberUpdatedState(shown)
    val currentOnToggle by rememberUpdatedState(onToggle)

    var trackWidthPx by remember { mutableIntStateOf(0) }
    val maxOffsetPx = with(density) {
        (trackWidthPx - (ThumbSize + TrackPadding * 2).roundToPx()).coerceAtLeast(0)
    }.toFloat()

    // While dragging, the thumb rides the finger; released, it springs to the shown state's end.
    var dragOffset by remember { mutableStateOf<Float?>(null) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val restingFraction = if (shown) 1f else 0f
    val fraction by animateFloatAsState(
        targetValue = dragOffset?.let { if (maxOffsetPx > 0f) it / maxOffsetPx else restingFraction }
            ?: restingFraction,
        animationSpec = if (dragOffset != null) snap() else PulseMotion.SpringPress,
        label = "rockerThumb",
    )
    val trackFill by animateColorAsState(
        targetValue = if (shown) pulse.effortDim else pulse.panelHigh,
        animationSpec = PulseMotion.standard(),
        label = "rockerTrack",
    )

    fun flipTo(next: Boolean) {
        if (next == currentShown) return
        if (next) haptics.toggleOn() else haptics.toggleOff()
        setTarget(next)
        currentOnToggle(next)
    }

    val interaction = remember { MutableInteractionSource() }
    val dragState = rememberDraggableState { delta ->
        dragged = ((dragOffset ?: dragged) + delta).coerceIn(0f, maxOffsetPx)
        dragOffset = dragged
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.sm)
            .height(TrackHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(trackFill)
            .border(1.dp, if (shown) pulse.effort.copy(alpha = 0.35f) else pulse.hairline, CircleShape)
            .onSizeChanged { trackWidthPx = it.width }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { flipTo(!currentShown) },
            )
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                startDragImmediately = false,
                onDragStarted = { dragged = if (currentShown) maxOffsetPx else 0f },
                onDragStopped = {
                    val landedOn = maxOffsetPx > 0f && dragged >= maxOffsetPx / 2f
                    dragOffset = null
                    flipTo(landedOn)
                },
            ),
    ) {
        Text(
            text = if (shown) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = if (shown) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(if (shown) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = HawksnestTheme.spacing.lg),
        )
        // The thumb: a lit disc when on, with a soft same-channel halo (tone, not shadow).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(TrackPadding)
                .offset { IntOffset((fraction * maxOffsetPx).roundToInt(), 0) }
                .size(ThumbSize)
                .drawBehind {
                    if (shown) {
                        drawCircle(pulse.effort.copy(alpha = 0.25f), radius = size.minDimension * 0.72f)
                    }
                }
                .clip(CircleShape)
                .background(if (shown) pulse.effort else pulse.hairlineStrong),
        )
    }
}
