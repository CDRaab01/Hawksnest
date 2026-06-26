package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Full-screen camera view. Plays the live MJPEG stream when one is available (showing the
 * auto-refreshing snapshot until the first frame), else just the refreshing snapshot. Dismisses on
 * the close button or a scrim tap; leaving tears the stream down.
 */
@Composable
fun CameraLightbox(
    name: String,
    snapshotUrl: String?,
    streamUrl: String?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bucket by produceState(0L) {
            while (true) {
                value = System.currentTimeMillis() / 2000
                delay(2000)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            val frameModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
            if (streamUrl != null) {
                // Live MJPEG (falls back to the snapshot internally until the first frame).
                MjpegView(streamUrl = streamUrl, snapshotUrl = snapshotUrl, bucket = bucket, modifier = frameModifier)
            } else {
                CameraSnapshot(model = bustCache(snapshotUrl, bucket), modifier = frameModifier)
            }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
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
