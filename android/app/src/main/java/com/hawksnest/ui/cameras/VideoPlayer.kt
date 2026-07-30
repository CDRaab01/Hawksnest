package com.hawksnest.ui.cameras

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    /** Reports the media duration (ms) once known — and again when it grows (an HLS event
     *  playlist's duration extends as segments append). */
    onDurationMs: ((Long) -> Unit)? = null,
    /** Fired on a fatal playback error (dead playlist, expired token) so the host can step down. */
    onError: (() -> Unit)? = null,
    /**
     * HA token sent as `Authorization: Bearer` on every media request.
     *
     * Required for Frigate VOD's nested `index-*.m3u8` manifest, which a URL signature cannot
     * cover (HA signs an exact path, not a prefix). Null for sources that need no auth — the
     * bundled demo clip, go2rtc live, and ring-timeline's pre-signed URLs.
     */
    authToken: String? = null,
) {
    val context = LocalContext.current
    val currentOnDurationMs by rememberUpdatedState(onDurationMs)
    val currentOnError by rememberUpdatedState(onError)
    val uri: Uri = if (url == DEMO_CLIP_URI) {
        RawResourceDataSource.buildRawResourceUri(R.raw.camera_loop)
    } else {
        Uri.parse(url)
    }
    // The bundled demo clip is a finite raw resource used as a fake-live LOOP — never treat it as a
    // live stream (that would stop it looping). Only real HLS URLs get live-edge handling.
    val liveStream = live && url != DEMO_CLIP_URI

    // Frigate VOD needs BOTH credentials on its requests, for different reasons:
    //
    //  * `authSig` on SEGMENTS. The integration validates it unconditionally and a Bearer token
    //    does NOT satisfy that check.
    //  * a Bearer token on the NESTED `index-*.m3u8` manifest. HA's signed-path auth validates the
    //    EXACT path signed, so the signature minted for `master.m3u8` does not authorise its
    //    sibling — measured: index with authSig alone is 401, with a Bearer it is 200.
    //
    // Both also matter because players resolve segment/manifest URIs RELATIVE to the master, which
    // drops the query string. Getting either half wrong yields a black video with no error, since
    // the master loads fine and everything after it 401s.
    //
    // Keyed on both so switching camera, page or session rebuilds the player with the right creds.
    val authSig = remember(url) { Uri.parse(url).getQueryParameter(AUTH_SIG_PARAM) }
    val player = remember(authSig, authToken) {
        val builder = ExoPlayer.Builder(context)
        if (authSig != null || authToken != null) {
            val http = DefaultHttpDataSource.Factory().apply {
                if (authToken != null) {
                    setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
                }
            }
            val factory: DataSource.Factory =
                if (authSig != null) AuthSigDataSourceFactory(http, authSig) else http
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(factory))
        }
        builder.build().apply { volume = 0f }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            fun reportDuration() {
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) currentOnDurationMs?.invoke(d)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) reportDuration()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                reportDuration()
            }
            override fun onPlayerError(error: PlaybackException) {
                currentOnError?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
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

/** Query parameter frigate-hass-integration requires on every VOD segment request. */
private const val AUTH_SIG_PARAM = "authSig"

/**
 * Wraps a [DataSource.Factory] so every request carries the manifest's `authSig`.
 *
 * Needed because ExoPlayer resolves HLS segment URIs relative to the manifest, which discards the
 * query string — so a correctly signed manifest still yields unsigned, 401ing segment requests.
 * One signature covers the whole window (the integration checks it as a path *prefix*), so this
 * simply re-attaches the same value rather than signing anything itself.
 *
 * Requests that already carry the parameter are passed through untouched, so this is safe to apply
 * to every request the player makes rather than trying to guess which ones are segments.
 */
@UnstableApi
private class AuthSigDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val authSig: String,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        AuthSigDataSource(delegate.createDataSource(), authSig)
}

/**
 * Delegates everything to [inner] except [open], where it re-attaches `authSig` to the URI.
 *
 * `by inner` keeps this forward-compatible: media3's `DataSource` grows methods between versions,
 * and delegation means new ones pass through instead of failing to compile or silently no-oping.
 */
@UnstableApi
private class AuthSigDataSource(
    private val inner: DataSource,
    private val authSig: String,
) : DataSource by inner {
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.getQueryParameter(AUTH_SIG_PARAM) != null) return inner.open(dataSpec)
        val signed = uri.buildUpon().appendQueryParameter(AUTH_SIG_PARAM, authSig).build()
        return inner.open(dataSpec.buildUpon().setUri(signed).build())
    }
}
