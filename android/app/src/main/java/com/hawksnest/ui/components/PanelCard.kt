package com.hawksnest.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The PULSE surface: a flat panel with a 1px hairline stroke instead of a shadow. Depth comes
 * from tone ([raised] uses the lighter panel) and stroke, never elevation.
 *
 * [channel] ties the panel to a data domain — it tints the stroke and is meant for panels whose
 * content is "live" in that channel (an active timer, a PR callout). Leave null for neutral
 * structure. When [onClick] is provided the whole panel is tappable with a press-scale.
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    channel: Color? = null,
    raised: Boolean = false,
    containerColor: Color? = null,
    contentPadding: Dp = HawksnestTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val shape = MaterialTheme.shapes.medium
    val color = containerColor ?: if (raised) pulse.panelHigh else pulse.panel
    val border = BorderStroke(1.dp, channel?.copy(alpha = 0.35f) ?: pulse.hairline)
    val inner: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }

    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.pressScale(interaction),
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            interactionSource = interaction,
            content = inner,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = inner,
        )
    }
}
