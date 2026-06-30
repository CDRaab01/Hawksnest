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
    /** True for a live HLS feed — join near the live edge instead of racing the buffer to catch up. */
    live: Boolean = false,
    /** Scrub position (ms into the media). Seeks the prepared player — no re-prepare/reload. */
    seekToMs: Long? = null,
) {
    val context = LocalContext.current
    val uri: Uri = if (url == DEMO_CLIP_URI) {
        RawResourceDataSource.buildRawResourceUri(R.raw.camera_loop)
    } else {
        Uri.parse(url)
    }
    // The bundled demo clip is a finite raw resource used as a fake-live LOOP — never treat it as a
    // live stream (that would stop it looping). Only real HLS URLs get live-edge handling.
    val liveStream = live && url != DEMO_CLIP_URI

    val player = remember {
        ExoPlayer.Builder(context).build().apply { volume = 0f }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Prepare only when the media (or loop mode) actually changes. Scrubbing keeps the same VOD
    // loaded and seeks within it (below) instead of re-preparing per move, which re-buffered
    // (stutter) and could crash ExoPlayer on a backwards seek.
    LaunchedEffect(uri, loop, liveStream) {
        // A live feed never ends, so REPEAT is meaningless — and looping a live MediaItem is wrong.
        player.repeatMode = if (loop && !liveStream) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val item = if (liveStream) {
            // Pin a small target offset so ExoPlayer joins NEAR the live edge instead of at the back
            // of HA's playlist and fast-forwarding to catch up (the confusing "time jump").
            MediaItem.Builder()
                .setUri(uri)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2_000).build(),
                )
                .build()
        } else {
            MediaItem.fromUri(uri)
        }
        player.setMediaItem(item)
        player.prepare()
        if (liveStream) player.seekToDefaultPosition()
        player.playWhenReady = !paused
    }

    LaunchedEffect(uri, seekToMs) {
        // Scrubbing only applies to VOD; a live stream has no meaningful seek target.
        if (!liveStream && seekToMs != null) {
            // Clamp into the loaded media (duration is UNSET until prepared) and guard the call:
            // an out-of-range/ill-timed seek must never throw out of this effect and kill the app.
            val dur = player.duration
            val target = seekToMs.coerceAtLeast(0L).let { if (dur > 0) it.coerceAtMost(dur) else it }
            runCatching { player.seekTo(target) }
        }
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
