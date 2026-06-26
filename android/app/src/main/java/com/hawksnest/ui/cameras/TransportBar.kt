package com.hawksnest.ui.cameras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Playback transport under the timeline — prev/next recorded event, play/pause, and a snap-to-Live
 * pill (Ring's ⏮ ⏸ ⏭ + Live). Stepping + live-state are driven by the parent. Mirrors the web
 * `TransportBar`.
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
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onPrev, enabled = canPrev) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous event")
        }
        FilledTonalIconButton(onClick = onTogglePlay, enabled = !isLive) {
            if (isPaused) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
            } else {
                Icon(Icons.Filled.Pause, contentDescription = "Pause")
            }
        }
        FilledTonalIconButton(onClick = onNext, enabled = canNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next event")
        }
        Surface(
            onClick = onLive,
            shape = RoundedCornerShape(50),
            color = if (isLive) HawksnestTheme.pulse.recovery else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                "Live",
                style = MaterialTheme.typography.labelLarge,
                color = if (isLive) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
