package com.hawksnest.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.hawksnest.core.logic.Tailscale
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The **Tailscale** panel. Hawksnest reaches Home Assistant over the device's Tailscale tunnel — the
 * official Tailscale app provides the VPN; this panel just helps the user install/open it, reminds
 * them the base URL should be a tailnet host, and offers a one-tap reachability probe so they can
 * confirm the tunnel routes to the proxy before chasing token/connection errors.
 */
@Composable
fun TailscalePanel(
    savedUrl: String?,
    reachability: Reachability,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pulse = HawksnestTheme.pulse
    val hasUrl = !savedUrl.isNullOrBlank()
    val looksTailnet = hasUrl && Tailscale.isTailnetHost(savedUrl!!)

    PanelCard(modifier = modifier) {
        Text(
            "Tailscale",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Hawksnest connects to Home Assistant over your tailnet — it doesn't run the VPN " +
                "itself. Install the Tailscale app, sign in, and set the Base URL above to your " +
                "tailnet host (a MagicDNS …ts.net name or a 100.x address).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = HawksnestTheme.spacing.xs),
        )

        // URL shape hint — only once a URL is saved, so it isn't nagging on a fresh install.
        if (hasUrl) {
            HintRow(
                icon = if (looksTailnet) Icons.Filled.CheckCircle else Icons.Filled.Info,
                tint = if (looksTailnet) pulse.recovery else pulse.streak,
                text = if (looksTailnet) {
                    "Base URL looks like a tailnet host."
                } else {
                    "Base URL isn't a tailnet host — fine on your LAN, but it won't reach home " +
                        "over Tailscale."
                },
            )
        }

        // Reachability probe result.
        when (reachability) {
            Reachability.Idle -> Unit
            Reachability.Checking -> HintRow(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Checking…")
            Reachability.Reachable -> HintRow(Icons.Filled.CheckCircle, pulse.recovery, "Reachable — the tunnel routes to your host.")
            Reachability.Unreachable -> HintRow(Icons.Filled.Error, pulse.streak, "Unreachable — is Tailscale connected and the URL correct?")
        }

        Row(
            modifier = Modifier.padding(top = HawksnestTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            PulseButton(
                text = "Open Tailscale",
                onClick = { openTailscale(context) },
                modifier = Modifier.weight(1f),
            )
            PulseButton(
                text = "Test reachability",
                onClick = onTest,
                modifier = Modifier.weight(1f),
                enabled = hasUrl && reachability != Reachability.Checking,
                tonal = true,
            )
        }
    }
}

@Composable
private fun HintRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(
        modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Launch the Tailscale app if installed; otherwise send the user to the Play Store / web listing. */
private fun openTailscale(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(Tailscale.PACKAGE)
    if (launch != null) {
        context.startActivity(launch)
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Tailscale.MARKET_URI)))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Tailscale.PLAY_URL)))
    }
}
