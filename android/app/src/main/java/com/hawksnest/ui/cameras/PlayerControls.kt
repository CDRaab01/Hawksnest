package com.hawksnest.ui.cameras

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save the camera's current snapshot to the device — the Reolink app's camera
 * button, saving into MediaStore (`Pictures/Hawksnest`) on Q+ where that needs
 * no permission, and into the app's external pictures dir on older API levels
 * (minSdk 26) rather than asking for the legacy storage permission.
 *
 * Failure is a transient inline state — a snapshot that didn't save is
 * self-evident, not worth a dialog.
 */
@Composable
fun SnapshotButton(snapshotUrl: String?, cameraName: String, modifier: Modifier = Modifier) {
    if (snapshotUrl == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SnapshotState.Idle) }
    LaunchedEffect(state) {
        if (state != SnapshotState.Idle) {
            delay(2500)
            state = SnapshotState.Idle
        }
    }
    ChromeButton(
        icon = { fg ->
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        },
        label = when (state) {
            SnapshotState.Idle -> "Snapshot"
            SnapshotState.Saved -> "Saved"
            SnapshotState.Failed -> "Failed"
        },
        active = state == SnapshotState.Saved,
        onClick = {
            scope.launch {
                state = if (saveSnapshot(context, snapshotUrl, cameraName)) {
                    SnapshotState.Saved
                } else {
                    SnapshotState.Failed
                }
            }
        },
        modifier = modifier,
    )
}

private enum class SnapshotState { Idle, Saved, Failed }

private suspend fun saveSnapshot(context: Context, url: String, cameraName: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
                .use { resp ->
                    check(resp.isSuccessful) { "snapshot ${resp.code}" }
                    resp.body!!.bytes()
                }
            val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
            val name = "$cameraName-$stamp.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hawksnest")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: error("no pictures dir")
                File(dir, name).writeBytes(bytes)
            }
            true
        }.getOrDefault(false)
    }

/** The shared chip look of the player-chrome row (matches SirenButton's frame). */
@Composable
private fun ChromeButton(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val bg = if (active) pulse.recoveryDim else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) pulse.recovery else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon(fg)
        // Never wrap. Without this a squeezed row renders the label one character
        // per line vertically, which is how the overflow bug showed up on-device
        // rather than as an obvious clip.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}
