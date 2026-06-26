package com.hawksnest.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Tactile press feedback: the element springs down slightly while pressed. Pair with an
 * [interactionSource] that's also fed to the click handler so the scale tracks real presses.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = PulseMotion.SpringPress,
        label = "pressScale",
    )
    this.scale(scale)
}

/**
 * The 1px PULSE stroke for surfaces that aren't a [PanelCard] (input fields, chips, strips).
 */
fun Modifier.hairline(
    shape: Shape,
    strong: Boolean = false,
): Modifier = composed {
    val pulse = HawksnestTheme.pulse
    this.border(1.dp, if (strong) pulse.hairlineStrong else pulse.hairline, shape)
}

/**
 * Animated shimmer fill for skeleton/loading placeholders. Sweeps a light band across a muted
 * base, looping forever.
 */
fun Modifier.shimmer(
    shape: Shape = RectangleShape,
    base: Color = Color.Gray.copy(alpha = 0.18f),
    highlight: Color = Color.Gray.copy(alpha = 0.34f),
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x, 0f),
                end = Offset(x + 400f, 0f),
            ),
        )
}
