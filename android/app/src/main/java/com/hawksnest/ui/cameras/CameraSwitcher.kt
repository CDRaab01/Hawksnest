package com.hawksnest.ui.cameras

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.home.CameraUi

/**
 * In-player camera dropdown (Ring's "Front ▾"). Switches the active camera without leaving the
 * player. Mirrors the web `CameraSwitcher`.
 */
@Composable
fun CameraSwitcher(
    cameras: List<CameraUi>,
    current: CameraUi,
    onSelect: (CameraUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val multi = cameras.size > 1

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = multi) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                current.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (multi) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose camera",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cameras.forEach { cam ->
                DropdownMenuItem(
                    text = { Text(cam.name) },
                    onClick = {
                        onSelect(cam)
                        expanded = false
                    },
                )
            }
        }
    }
}
