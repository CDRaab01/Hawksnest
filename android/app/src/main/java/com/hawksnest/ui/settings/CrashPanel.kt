package com.hawksnest.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme

/** One captured crash: a headline for the list and the full scrubbed body. */
data class CrashEntry(val id: String, val headline: String, val body: String)

/**
 * Recent crashes, so a crash on the phone can be read on the phone.
 *
 * Without this the report exists only on disk and over adb, which for a sideloaded app on a phone
 * that is usually nowhere near a cable means it may as well not exist. The ntfy push tells you
 * *that* it crashed and roughly where; this is where you read the rest.
 *
 * Empty state is deliberately reassuring rather than absent — "no crashes" is information, and a
 * section that vanishes when healthy is a section nobody trusts is working.
 */
@Composable
fun CrashPanel(
    crashes: List<CrashEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier.testTag("crashPanel")) {
        if (crashes.isEmpty()) {
            Text(
                "No crashes recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "If the app ever stops unexpectedly, the details appear here — and are sent to " +
                    "your ntfy topic if push alerts are on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HawksnestTheme.spacing.xs),
            )
            return@PanelCard
        }

        Text(
            if (crashes.size == 1) "1 recent crash" else "${crashes.size} recent crashes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        crashes.forEach { entry -> CrashRow(entry) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HawksnestTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            PulseButton(
                text = "Clear",
                onClick = onClear,
                tonal = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CrashRow(entry: CrashEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.sm)
            .clickable { expanded = !expanded }
            .testTag("crashRow"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = HawksnestTheme.pulse.streak,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            // Monospace and horizontally scrollable: a stack trace with wrapped lines is much
            // harder to read, and the frame + line number sit at the end of each line.
            Text(
                entry.body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = HawksnestTheme.spacing.xs)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}
