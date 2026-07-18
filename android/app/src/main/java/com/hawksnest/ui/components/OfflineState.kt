package com.hawksnest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.formatAsOf
import com.hawksnest.core.logic.retryCountdownSeconds
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.delay

/**
 * The honest full-screen offline state, shown when Home Assistant can't be reached (terminal
 * auth error, or an in-session drop that outlived the 120s grace window). Deliberately renders
 * NO entity data — offline means "we don't know", never a stale snapshot. Carries the
 * last-connected readout, a live "Retrying in Ns" countdown off the reconnect loop's next
 * attempt, a Retry button that skips the remaining backoff, and the passive reachability hint
 * (is the network down, or just HA?). Mirrors the web `src/components/OfflineState.tsx`.
 */
@Composable
fun OfflineState(
    lastConnectedMs: Long?,
    nextRetryAtMs: Long?,
    hostReachable: Boolean?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val pulse = HawksnestTheme.pulse
    // 1s heartbeat so the countdown and the "as of" readout stay live.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(pulse.streakDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = pulse.streak,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "Can't reach Home Assistant",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (lastConnectedMs != null) {
            Text(
                text = "Last connected ${formatAsOf(lastConnectedMs, nowMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (hostReachable != null) {
            Text(
                text = if (hostReachable) {
                    "Home network is reachable — Home Assistant isn't answering."
                } else {
                    "Your home network is unreachable — check Tailscale."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (nextRetryAtMs != null) {
            val seconds = retryCountdownSeconds(nextRetryAtMs, nowMs)
            Text(
                text = if (seconds > 0) "Retrying in ${seconds}s" else "Retrying…",
                style = MaterialTheme.typography.labelMedium,
                color = pulse.streak,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Box(Modifier.padding(top = 20.dp)) {
            PulseButton(text = "Retry now", onClick = onRetry)
        }
    }
}

/**
 * The grace-window banner: an in-session drop keeps the last in-memory entities on screen —
 * dimmed, controls disabled — for at most 120s, and this persistent strip says exactly how old
 * they are. Lock/alarm state is excluded from the grace treatment entirely (masked to
 * `unavailable` the moment the socket drops — see `core/logic/Offline.kt`).
 */
@Composable
fun ReconnectingBanner(asOfMs: Long?, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    PanelCard(modifier = modifier.fillMaxWidth(), channel = pulse.streak) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = pulse.streak,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (asOfMs != null) {
                    "Reconnecting — as of ${formatAsOf(asOfMs)}"
                } else {
                    "Reconnecting…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = pulse.streak,
                modifier = Modifier.padding(start = HawksnestTheme.spacing.sm),
            )
        }
    }
}
