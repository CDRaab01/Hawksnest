package com.hawksnest.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.hawksnest.core.logic.isPlausibleIpv4
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Settings panel for the direct-to-camera RTSP tier.
 *
 * Kept out of `SettingsScreen` because it carries its own draft-edit state for a variable-length
 * list. Deliberately empty by default and free-form: camera names and addresses are per-household,
 * and this repo is public — nothing here ships with a real IP or account baked in.
 */
@Composable
fun RtspPanel(
    savedUser: String?,
    hasPass: Boolean,
    savedCameraIps: Map<String, String>,
    onSave: (user: String, pass: String, cameraIps: Map<String, String>) -> Unit,
    onClear: () -> Unit,
) {
    var user by remember(savedUser) { mutableStateOf(savedUser.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    // Draft rows, so a half-typed IP never reaches storage (and so a row can exist while empty).
    var rows by remember(savedCameraIps) {
        mutableStateOf(savedCameraIps.entries.map { it.key to it.value }.ifEmpty { listOf("" to "") })
    }

    val validRows = rows.filter { (name, ip) -> name.isNotBlank() && isPlausibleIpv4(ip) }
    val configured = savedUser?.isNotBlank() == true && hasPass && savedCameraIps.isNotEmpty()

    PanelCard {
        Text(
            if (configured) {
                "Active for ${savedCameraIps.size} camera(s). The player uses this first and falls " +
                    "back to the usual stream if a camera doesn't answer."
            } else {
                "Optional. Plays the camera's own stream directly — the smoothest option — instead " +
                    "of a relayed one. Needs a camera account and each camera's IP."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Camera username") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HawksnestTheme.spacing.md),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(if (hasPass) "New camera password (one saved)" else "Camera password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HawksnestTheme.spacing.sm),
        )

        Text(
            "Cameras",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = HawksnestTheme.spacing.md),
        )
        rows.forEachIndexed { i, (name, ip) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HawksnestTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v -> rows = rows.toMutableList().also { it[i] = v to ip } },
                    label = { Text("Name") },
                    placeholder = { Text("big_room") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { v -> rows = rows.toMutableList().also { it[i] = name to v } },
                    label = { Text("IP") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    // Only flag a wrong-looking address once there's something to be wrong about.
                    isError = ip.isNotBlank() && !isPlausibleIpv4(ip),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            PulseButton(
                text = "Add camera",
                onClick = { rows = rows + ("" to "") },
                tonal = true,
                modifier = Modifier.weight(1f),
            )
            if (rows.size > 1) {
                PulseButton(
                    text = "Remove last",
                    onClick = { rows = rows.dropLast(1) },
                    tonal = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = HawksnestTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            PulseButton(
                text = "Save",
                onClick = { onSave(user, pass, validRows.toMap()); pass = "" },
                modifier = Modifier.weight(1f),
                // A password is required once, but not on every edit — the stored one is kept.
                enabled = user.isNotBlank() && (pass.isNotBlank() || hasPass) && validRows.isNotEmpty(),
            )
            if (configured) {
                PulseButton(
                    text = "Turn off",
                    onClick = { onClear(); pass = ""; rows = listOf("" to "") },
                    modifier = Modifier.weight(1f),
                    tonal = true,
                    channel = HawksnestTheme.pulse.streak,
                    onChannel = HawksnestTheme.pulse.onStreak,
                    dimChannel = HawksnestTheme.pulse.streakDim,
                )
            }
        }

        Text(
            "Name must match the camera's name in Home Assistant (camera.big_room → big_room). " +
                "Use a view-only camera account, not an admin one. Away from home this needs the " +
                "camera's address routed onto your tailnet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
        )
    }
}
