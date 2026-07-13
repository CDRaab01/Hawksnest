package com.hawksnest.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()
    val savedUrl by viewModel.savedUrl.collectAsState()
    val hasToken by viewModel.hasToken.collectAsState()
    val reachability by viewModel.reachability.collectAsState()
    val pushEnabled by viewModel.pushEnabled.collectAsState()

    val context = LocalContext.current
    // Android 13+ gates notifications behind POST_NOTIFICATIONS; request it the
    // first time push is switched on, then persist + start the listener on grant.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.setPushEnabled(true) }

    // Battery-optimization exemption: One UI kills long-idle foreground services, so the ntfy
    // listener can go silent after days. Offer a one-tap exemption; re-check on return from the
    // system dialog via the launcher's result callback (no lifecycle observer needed).
    var batteryExempt by remember { mutableStateOf(isIgnoringBattery(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryExempt = isIgnoringBattery(context) }

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
                    .padding(top = HawksnestTheme.spacing.md)
                    .testTag("settingsUrlField"),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (hasToken) "New long-lived token (one saved)" else "Long-lived token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HawksnestTheme.spacing.sm)
                    .testTag("settingsTokenField"),
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

        SectionHeader("Notifications")
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Push alerts",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Doorbell rings and alarm changes, even when the app is closed. " +
                            "Delivered over your tailnet via ntfy — no Google account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = HawksnestTheme.spacing.xs),
                    )
                }
                Switch(
                    checked = pushEnabled,
                    onCheckedChange = { want ->
                        if (want) {
                            val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            if (needsPerm) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setPushEnabled(true)
                            }
                        } else {
                            viewModel.setPushEnabled(false)
                        }
                    },
                    modifier = Modifier.testTag("settingsPushSwitch"),
                )
            }
            // Only when push is on and Android is still allowed to doze the listener.
            if (pushEnabled && !batteryExempt) {
                Text(
                    "Battery optimization is on — Android may stop delivering alerts after the " +
                        "app sits idle for a while. Allow background activity for reliable pushes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HawksnestTheme.pulse.streak,
                    modifier = Modifier.padding(top = HawksnestTheme.spacing.md),
                )
                PulseButton(
                    text = "Allow background delivery",
                    onClick = { batteryLauncher.launch(batteryExemptionIntent(context)) },
                    tonal = true,
                    modifier = Modifier
                        .padding(top = HawksnestTheme.spacing.sm)
                        .testTag("settingsBatteryButton"),
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

/** Whether Android is already letting the push listener run unthrottled. */
private fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

// Opens the system "allow background activity?" prompt for this app. Lint flags this action as
// Play-restricted (BatteryLife); Hawksnest is sideloaded (suite app), and reliable doorbell
// delivery is exactly the sanctioned use case, so the suppression is deliberate.
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
