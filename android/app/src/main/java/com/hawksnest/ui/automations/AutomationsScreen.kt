package com.hawksnest.ui.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.components.rememberOptimisticOnOff
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Automations — the top-level tab listing HA automations (each an `automation.*` entity), each with
 * Run-now, an enable/disable switch, and an Edit affordance into the builder. A "New" button starts
 * a fresh automation. HA runs them; Hawksnest lists, toggles, runs, and edits via the Config API.
 */
@Composable
fun AutomationsScreen(
    onNew: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val automations by viewModel.automations.collectAsState()
    val isDemo by viewModel.isDemo.collectAsState()
    val pulse = HawksnestTheme.pulse

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HawksnestTheme.spacing.lg, vertical = HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader(
            "Automations",
            channel = pulse.effort,
            trailing = {
                PulseButton(text = "New", onClick = onNew, compact = true)
            },
        )

        if (isDemo) {
            Text(
                "Demo mode — automations are simulated locally. Connect Home Assistant in Settings " +
                    "to create ones that actually run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (automations.isEmpty()) {
            PanelCard {
                Text(
                    "No automations yet. Tap New to link your devices — e.g. \"when the alarm is " +
                        "armed, lock every door\" or \"turn on the porch light at sunset.\"",
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
                        RunButton(onRun = { viewModel.run(a.entityId) })
                        if (a.configId != null) {
                            IconButton(onClick = { onEdit(a.configId) }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Edit ${a.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Optimistic enable: the switch follows the tap, the entity echo reconciles.
                        val haptics = rememberHaptics()
                        val (shown, setTarget) = rememberOptimisticOnOff(a.enabled, pending = false)
                        Switch(
                            checked = shown,
                            onCheckedChange = { desired ->
                                if (desired) haptics.toggleOn() else haptics.toggleOff()
                                setTarget(desired)
                                viewModel.setEnabled(a.entityId, desired)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Run now" with feedback: an automation's effect is invisible, so the button
 * flips to a check + "Ran" for ~1.6s after firing, then returns to "Run".
 */
@Composable
private fun RunButton(onRun: () -> Unit) {
    var ran by remember { mutableStateOf(false) }
    LaunchedEffect(ran) {
        if (ran) {
            kotlinx.coroutines.delay(1600)
            ran = false
        }
    }
    if (ran) {
        PulseButton(
            text = "Ran",
            onClick = {},
            tonal = true,
            compact = true,
            channel = HawksnestTheme.pulse.recovery,
            onChannel = HawksnestTheme.pulse.onRecovery,
            dimChannel = HawksnestTheme.pulse.recoveryDim,
            leadingIcon = {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = HawksnestTheme.pulse.recovery,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    } else {
        PulseButton(
            text = "Run",
            onClick = { onRun(); ran = true },
            tonal = true,
            compact = true,
        )
    }
}
