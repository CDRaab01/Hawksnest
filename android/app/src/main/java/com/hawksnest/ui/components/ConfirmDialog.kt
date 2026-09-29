package com.hawksnest.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The one check before an action that can't be taken back: clearing or revoking a keypad code,
 * deleting an automation, disconnecting from Home Assistant. Each used to happen on a single tap.
 *
 * Says what will happen in [text], in the owner's terms, and names the action on the confirm
 * button ([confirmLabel]) rather than a generic "OK", so a reflexive tap still reads what it does.
 * Cancel is the dismiss button and the outside tap, so the easy way out is the safe one.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = pulse.streak)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
            }
        },
        // The app's own panel, not Material's default tonal surface, which falls back to lavender.
        containerColor = pulse.panelHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
