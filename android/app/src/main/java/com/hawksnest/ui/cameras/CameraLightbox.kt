package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.home.CameraUi
import kotlinx.coroutines.delay

/**
 * Full-screen camera view hosting the Ring-style [CameraPlayer] (live + timeline scrubber +
 * transport + in-player switcher). Mounts only while open; dismisses on the close button or a scrim
 * tap. Tracks the switched-to camera locally so the player can change feeds without closing.
 * Mirrors the web `CameraLightbox`.
 */
@Composable
fun CameraLightbox(
    cameras: List<CameraUi>,
    initial: CameraUi,
    onDismiss: () -> Unit,
    viewModel: CameraPlayerViewModel = hiltViewModel(),
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var current by remember { mutableStateOf(initial) }
        val bucket by produceState(0L) {
            while (true) {
                value = System.currentTimeMillis() / 2000
                delay(2000)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            CameraPlayer(
                cam = current,
                cameras = cameras.ifEmpty { listOf(initial) },
                onSelectCamera = { current = it },
                viewModel = viewModel,
                bucket = bucket,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
