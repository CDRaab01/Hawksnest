package com.hawksnest.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.BuildConfig
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Settings — the Connection panel: enter the Home Assistant base URL + a long-lived token, Connect /
 * Disconnect, with a live status pill. No token → the app stays on demo data.
 */
@Composable
fun SettingsScreen(
    onOpenAutomations: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()
    val savedUrl by viewModel.savedUrl.collectAsState()
    val hasToken by viewModel.hasToken.collectAsState()
    val reachability by viewModel.reachability.collectAsState()

    val defaultUrl = savedUrl ?: BuildConfig.HA_DEFAULT_URL
    var url by remember(defaultUrl) { mutableStateOf(defaultUrl) }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader("Connection")
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Home Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ConnectionPill(status)
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = HawksnestTheme.pulse.streak,
                    modifier = Modifier.padding(top = HawksnestTheme.spacing.xs),
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Base URL") },
                placeholder = { Text("http://hawksnest.<tailnet>.ts.net:8080") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HawksnestTheme.spacing.md),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (hasToken) "New long-lived token (one saved)" else "Long-lived token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HawksnestTheme.spacing.sm),
            )
            Row(
                modifier = Modifier.padding(top = HawksnestTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
            ) {
                PulseButton(
                    text = "Connect",
                    onClick = { viewModel.connect(url, token); token = "" },
                    modifier = Modifier.weight(1f),
                    enabled = url.isNotBlank() && (token.isNotBlank() || hasToken),
                )
                if (hasToken) {
                    PulseButton(
                        text = "Disconnect",
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.weight(1f),
                        tonal = true,
                        channel = HawksnestTheme.pulse.streak,
                        onChannel = HawksnestTheme.pulse.onStreak,
                        dimChannel = HawksnestTheme.pulse.streakDim,
                    )
                }
            }
            Text(
                "Create a token in Home Assistant → Profile → Long-lived access tokens. URL is the " +
                    "Hawksnest proxy on your tailnet (…:8080) or your HA directly (…:8123). No token = demo data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
            )
        }

        SectionHeader("Tailscale")
        TailscalePanel(
            savedUrl = savedUrl,
            reachability = reachability,
            onTest = { viewModel.testReachability(url) },
        )

        SectionHeader("Automation")
        PanelCard(onClick = onOpenAutomations) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Automations",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "View, enable, and run your Home Assistant automations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader("About")
        PanelCard {
            Text(
                "Hawksnest ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
