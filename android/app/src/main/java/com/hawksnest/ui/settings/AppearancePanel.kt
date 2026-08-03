package com.hawksnest.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.hawksnest.core.logic.ThemePref
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Dark / Light / System, mirroring the web Settings → Appearance control.
 *
 * The light palette has existed since the V1 gates (`LightColors`, `lightPulseColors()`) and
 * `HawksnestTheme` has always taken `darkTheme` as a parameter — the only missing piece was a way
 * to set it. Until now Android hardcoded `isSystemInDarkTheme()` while web offered all three,
 * which the audit filed as the cheapest real parity gap in the app.
 *
 * Segment styling matches the Devices filter chips rather than stock M3, so Settings does not
 * introduce a second visual language for the same interaction.
 */
@Composable
fun AppearancePanel(
    pref: ThemePref,
    onSelect: (ThemePref) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    PanelCard(modifier = modifier.testTag("appearancePanel")) {
        Text(
            "Theme",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HawksnestTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            ThemePref.entries.forEach { option ->
                val active = option == pref
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (active) pulse.effortDim else pulse.panelHigh)
                        .clickable { onSelect(option) }
                        .padding(vertical = HawksnestTheme.spacing.sm)
                        .testTag("theme_${option.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "System follows your phone's day/night setting. Dark is the design default — the " +
                "palette was built for an OLED panel in a dark room.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
        )
    }
}
