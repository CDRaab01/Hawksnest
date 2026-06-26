package com.hawksnest.ui.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Automations — a list of HA automations (each an `automation.*` entity) with Run-now and an
 * enable/disable switch. Editing the automation config is deferred to a follow-up. Ported from the
 * web `AutomationsScreen`.
 */
@Composable
fun AutomationsScreen(
    onBack: () -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val automations by viewModel.automations.collectAsState()
    val isDemo by viewModel.isDemo.collectAsState()
    val pulse = HawksnestTheme.pulse

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HawksnestTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Automations",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            SectionHeader("Automations", channel = pulse.effort)

            if (isDemo) {
                Text(
                    "Demo mode — automations are simulated locally. Connect Home Assistant in " +
                        "Settings to control ones that actually run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (automations.isEmpty()) {
                PanelCard {
                    Text(
                        "No automations yet. Create them in Home Assistant to link your devices — " +
                            "e.g. \"when the alarm is armed, lock every door.\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PanelCard {
                    automations.forEach { a ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = HawksnestTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    a.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${if (a.enabled) "Enabled" else "Disabled"} · ${a.lastTriggered}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            PulseButton(
                                text = "Run",
                                onClick = { viewModel.run(a.entityId) },
                                tonal = true,
                                compact = true,
                            )
                            Switch(
                                checked = a.enabled,
                                onCheckedChange = { desired -> viewModel.setEnabled(a.entityId, desired) },
                                colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
                            )
                        }
                    }
                }
            }
        }
    }
}
