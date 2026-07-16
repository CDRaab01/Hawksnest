package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Playback transport under the timeline — prev/next moment, play/pause, and a snap-to-Live pill
 * (Ring's ⏮ ⏸ ⏭ + Live), as big round buttons. Stepping + live-state are driven by the parent.
 * Mirrors the web `TransportBar`.
 */
@Composable
fun TransportBar(
    isLive: Boolean,
    isPaused: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            icon = Icons.Filled.SkipPrevious,
            desc = "Previous moment",
            onClick = onPrev,
            enabled = canPrev,
            diameter = 52.dp,
        )
        RoundButton(
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            desc = if (isPaused) "Play" else "Pause",
            onClick = onTogglePlay,
            enabled = !isLive,
            diameter = 64.dp,
        )
        RoundButton(
            icon = Icons.Filled.SkipNext,
            desc = "Next moment",
            onClick = onNext,
            enabled = canNext,
            diameter = 52.dp,
        )
        Surface(
            onClick = onLive,
            shape = RoundedCornerShape(50),
            color = if (isLive) HawksnestTheme.pulse.recovery else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Text(
                "Live",
                style = MaterialTheme.typography.labelLarge,
                color = if (isLive) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** A large circular transport control; dims to 40% when disabled. */
@Composable
private fun RoundButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    enabled: Boolean,
    diameter: Dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(diameter),
    ) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = desc,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(diameter * 0.46f),
            )
        }
    }
}
