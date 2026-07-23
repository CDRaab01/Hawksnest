package com.hawksnest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion

/**
 * Premium media transport: prev / play-pause / next as circular instrument discs — hairline
 * strokes on the panel tone, the center disc larger and lit in the effort channel while playing.
 * Every tap is a single service call (stateless transport; no pending choreography needed) with
 * a toggle haptic on play/pause and press-scale on all three.
 */
@Composable
fun MediaTransport(
    playing: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val centerFill by animateColorAsState(
        targetValue = if (playing) pulse.effortDim else pulse.panelHigh,
        animationSpec = PulseMotion.standard(),
        label = "transportFill",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.lg, Alignment.CenterHorizontally),
    ) {
        TransportDisc(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Previous",
            tint = MaterialTheme.colorScheme.onSurface,
            fill = pulse.panelHigh,
            size = 48.dp,
            onClick = onPrev,
        )
        TransportDisc(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = "Play/Pause",
            tint = pulse.effort,
            fill = centerFill,
            size = 56.dp,
            onClick = {
                if (playing) haptics.toggleOff() else haptics.toggleOn()
                onPlayPause()
            },
        )
        TransportDisc(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Next",
            tint = MaterialTheme.colorScheme.onSurface,
            fill = pulse.panelHigh,
            size = 48.dp,
            onClick = onNext,
        )
    }
}

@Composable
private fun TransportDisc(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    fill: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, pulse.hairline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}
