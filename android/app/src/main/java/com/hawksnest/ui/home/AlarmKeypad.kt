package com.hawksnest.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme

private const val MAX_CODE = 8

/**
 * Numeric PIN keypad for disarming an alarm panel that enforces a code (HA `code_format`). Collects
 * the digits locally and hands the finished code to [onSubmit] — the code is forwarded to HA as
 * disarm service data; it's never stored.
 */
@Composable
fun AlarmKeypadDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        PanelCard {
            Text(
                "Enter code to disarm",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (code.isEmpty()) " " else "•".repeat(code.length),
                style = HawksnestTheme.dataType.dataLarge,
                color = HawksnestTheme.pulse.effort,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = HawksnestTheme.spacing.md),
            )
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
                ) {
                    row.forEach { d -> Key(d) { if (code.length < MAX_CODE) code += d } }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
            ) {
                KeyIcon { if (code.isNotEmpty()) code = code.dropLast(1) }
                Key("0") { if (code.length < MAX_CODE) code += "0" }
                // Spacer cell keeps the "0" centered under the column.
                Box(Modifier.weight(1f))
            }
            PulseButton(
                text = "Disarm",
                onClick = { onSubmit(code) },
                modifier = Modifier.fillMaxWidth().padding(top = HawksnestTheme.spacing.sm),
                enabled = code.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun RowScope.Key(label: String, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = Modifier
            .weight(1f)
            .size(64.dp)
            .clip(CircleShape)
            .background(pulse.panelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = HawksnestTheme.dataType.dataMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.KeyIcon(onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Box(
        modifier = Modifier
            .weight(1f)
            .size(64.dp)
            .clip(CircleShape)
            .background(pulse.panelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "DEL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
