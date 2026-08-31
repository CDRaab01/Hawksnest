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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Tune
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
 * Player-chrome controls shared by every camera: audio mute, snapshot-to-file
 * and the Low/High live-quality toggle. Twins of the web's `MuteButton.tsx`,
 * `SnapshotButton.tsx` and `QualityToggle.tsx` — keep behaviour in lockstep.
 */

/** Opens the camera-movement drawer. Only shown for cameras that can actually move. */
@Composable
fun MoveButton(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** False on a doorbell: the drawer then holds settings, not a movement pad. */
    hasPtz: Boolean = true,
) {
    ChromeButton(
        icon = { fg ->
            Icon(
                if (hasPtz) Icons.Filled.OpenWith else Icons.Filled.Tune,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        },
        label = if (hasPtz) "Move" else "Settings",
        active = active,
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * Fullscreen toggle. A 16:9 frame on a tall phone uses about a third of the screen, so this is
 * the difference between glancing at a camera and actually looking at one.
 *
 * Deliberately a chrome button rather than a gesture: a double-tap already means "reset zoom"
 * (see [ZoomableFrame]) and overloading it would make both feel unreliable.
 */
@Composable
fun FullscreenButton(active: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ChromeButton(
        icon = { fg ->
            Icon(
                if (active) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        },
        label = if (active) "Exit" else "Full",
        active = active,
        onClick = onToggle,
        modifier = modifier,
    )
}

/** Speaker toggle. Every player mounts muted; this is the way back to sound. */
@Composable
fun MuteButton(muted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ChromeButton(
        icon = { fg ->
            Icon(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        },
        label = if (muted) "Muted" else "Sound",
        active = !muted,
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * Live-quality selector — the Reolink app's Low/High pill. High = the go2rtc
 * main stream; Low = the camera's `_sub` stream at a fraction of the bandwidth
 * (the practical answer to fixed-bitrate main streams stalling on weak
 * cellular). Rendered only when go2rtc actually lists the `_sub` stream.
 */
@Composable
fun QualityToggle(low: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        QualityChip("Low", selected = low) { onChange(true) }
        QualityChip("High", selected = !low) { onChange(false) }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        color = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Save the camera's current snapshot to the device — the Reolink app's camera
 * button, saving into MediaStore (`Pictures/Hawksnest`) on Q+ where that needs
 * no permission, and into the app's external pictures dir on older API levels
 * (minSdk 26) rather than asking for the legacy storage permission.
 *
 * Failure is a transient inline state — a snapshot that didn't save is
 * self-evident, not worth a dialog.
 */
/**
 * Enters/leaves clip-export mode. Frigate-only and recorded-only; the gate lives at the call site
 * in [CameraPlayer], not here, so there is exactly one place that decides who gets this control.
 */
@Composable
fun ClipButton(active: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ChromeButton(
        icon = { tint -> Icon(Icons.Filled.ContentCut, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp)) },
        label = "Clip",
        active = active,
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * Opens the prerecorded-message sheet. A plain chip like its neighbours on purpose — the whole
 * row reads as one set of controls, and the version that gave Reply a taller treatment of its own
 * is what made the row look mismatched on-device.
 */
@Composable
fun ReplyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeButton(
        icon = { tint -> Icon(Icons.Filled.Campaign, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp)) },
        label = "Reply",
        active = false,
        onClick = onClick,
        modifier = modifier,
    )
}

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
