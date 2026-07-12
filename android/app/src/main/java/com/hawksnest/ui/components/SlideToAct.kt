package com.hawksnest.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.PulseMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fraction of the track the thumb must cross for release to commit the action. */
private const val COMMIT_FRACTION = 0.8f

private val TrackHeight = 56.dp
private val ThumbSize = 44.dp
private val TrackPadding = 6.dp

/**
 * A slide-to-act track — the control for actions that must be deliberate (unlocking the front
 * door). The drag *is* the confirmation: a tap does nothing, crossing the commit point buzzes
 * ([Haptics.threshold]), and releasing past it fires [onCommit] with a confirm tick.
 *
 * Honest, non-optimistic pending: while [pending] the thumb holds at the far end with a spinner
 * and [pendingLabel] ("Unlocking…"); when the echo lands the parent flips [pending]/state and the
 * thumb springs home showing the *new* action. The control never shows a state HA hasn't
 * confirmed — this is the lock invariant rendered as a feature instead of lag.
 */
@Composable
fun SlideToAct(
    label: String,
    pendingLabel: String,
    icon: ImageVector,
    channel: Color,
    onChannel: Color,
    dimChannel: Color,
    pending: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var trackWidthPx by remember { mutableIntStateOf(0) }
    val maxOffsetPx = with(density) {
        (trackWidthPx - (ThumbSize + TrackPadding * 2).roundToPx()).coerceAtLeast(0)
    }.toFloat()

    val offset = remember { Animatable(0f) }
    // True once this drag crossed the commit point (drives the arming haptic exactly once).
    var armed by remember { mutableStateOf(false) }

    // Pending holds the thumb at the end; the echo (pending → false) springs it home. Also the
    // landing spot after a committed release, so the thumb doesn't flicker home and back while
    // the call is dispatched.
    LaunchedEffect(pending, maxOffsetPx) {
        if (maxOffsetPx <= 0f) return@LaunchedEffect
        if (pending) {
            offset.animateTo(maxOffsetPx, PulseMotion.standard())
        } else if (offset.value > 0f) {
            offset.animateTo(0f, PulseMotion.emphasized())
        }
    }

    val dragState = rememberDraggableState { delta ->
        val new = (offset.value + delta).coerceIn(0f, maxOffsetPx)
        val crossed = maxOffsetPx > 0f && new >= maxOffsetPx * COMMIT_FRACTION
        if (crossed && !armed) {
            armed = true
            haptics.threshold()
        } else if (!crossed && armed) {
            armed = false
        }
        scope.launch { offset.snapTo(new) }
    }

    val progress = if (maxOffsetPx > 0f) offset.value / maxOffsetPx else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(dimChannel)
            .onSizeChanged { trackWidthPx = it.width }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled && !pending,
                onDragStopped = {
                    val commit = armed
                    armed = false
                    if (commit) {
                        haptics.confirm()
                        scope.launch { offset.animateTo(maxOffsetPx, PulseMotion.fast()) }
                        onCommit()
                    } else {
                        scope.launch { offset.animateTo(0f, PulseMotion.emphasized()) }
                    }
                },
            ),
    ) {
        // Fill trailing the thumb — the track "charges" with the channel color as you slide.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(with(density) { (offset.value + (ThumbSize + TrackPadding * 2).toPx()).toDp() })
                .clip(CircleShape)
                .background(channel.copy(alpha = 0.25f)),
        )
        // Hint label, fading out as the slide progresses so the gesture feels
        // responsive. Centered in the space RIGHT of the resting thumb — centering
        // across the whole track put the first characters under the thumb on
        // narrow cards ("e to unlock").
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(start = ThumbSize + TrackPadding * 2, end = TrackPadding * 2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (pending) pendingLabel else label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer { alpha = if (pending) 1f else (1f - progress * 1.4f).coerceIn(0f, 1f) },
            )
        }
        // The thumb: channel disc carrying the action icon, or a spinner while pending.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(TrackPadding)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(ThumbSize)
                .clip(CircleShape)
                .background(channel),
            contentAlignment = Alignment.Center,
        ) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = onChannel,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(icon, contentDescription = label, tint = onChannel, modifier = Modifier.size(22.dp))
            }
        }
    }
}
