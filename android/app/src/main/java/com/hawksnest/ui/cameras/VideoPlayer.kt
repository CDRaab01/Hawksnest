package com.hawksnest.ui.cameras

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hawksnest.R
import com.hawksnest.core.logic.DEMO_CLIP_URI

/**
 * ExoPlayer-backed `<video>` for camera footage — the live HLS feed (the `media3-exoplayer-hls`
 * module auto-detects `.m3u8`), recorded VOD, and the bundled demo clip. The demo source returns
 * the [DEMO_CLIP_URI] sentinel, which maps to `R.raw.camera_loop` so demo plays real moving pixels
 * with no backend. Mirrors the web `HlsPlayer`. The MJPEG tier stays on the OkHttp [MjpegView].
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    loop: Boolean = false,
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val uri: Uri = if (url == DEMO_CLIP_URI) {
        RawResourceDataSource.buildRawResourceUri(R.raw.camera_loop)
    } else {
        Uri.parse(url)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { volume = 0f }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(uri, loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = !paused
    }

    LaunchedEffect(paused) {
        player.playWhenReady = !paused
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
    )
}
