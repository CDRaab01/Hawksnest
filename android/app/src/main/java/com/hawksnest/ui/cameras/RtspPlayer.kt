package com.hawksnest.ui.cameras

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.hawksnest.core.net.RtspHealth
import kotlinx.coroutines.delay

/**
 * The top live tier: RTSP straight to the camera, no relay.
 *
 * Separate from [VideoPlayer] rather than another mode inside it. VideoPlayer's player construction
 * is built around HLS/VOD concerns — the `authSig`/Bearer data-source wrapping, live-edge target
 * offsets, seek-within-loaded-media, duration reporting — none of which apply to a continuous RTSP
 * feed, and threading a MediaSource override through it would entangle the two for no shared code.
 *
 * Fails fast by design. This tier is optional: any failure — camera off, wrong credentials, out of
 * RTSP sessions, unroutable IP — must land on go2rtc quickly rather than leaving a dead frame, so
 * everything here is a race between "playing" and a short deadline.
 */
@OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    /** Full `rtsp://user:pass@host/path`. **Never log this** — see `redactRtspUrl`. */
    url: String,
    /** Camera name, for the per-camera circuit-breaker. */
    camera: String,
    onFail: () -> Unit,
    /** Audio gate — defaults muted; the chrome's MuteButton is the way to sound. */
    muted: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnFail by rememberUpdatedState(onFail)
    var ready by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    // Rises while playback is stalled; reset whenever it is not.
    var bufferingSince by remember(url) { mutableStateOf<Long?>(null) }

    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                // TCP interleaving, not UDP. The camera is reached over the tailnet (a WireGuard
                // tunnel) as often as on the LAN, and RTP-over-UDP needs its own ports to survive
                // that path; interleaving the media into the RTSP control connection needs exactly
                // one. Marginally more jitter-prone under loss, and still continuous — unlike the
                // segmented HLS tier this exists to leapfrog.
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(CONNECT_TIMEOUT_MS),
            )
            .build()
            .apply { volume = if (muted) 0f else 1f }
    }
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ready = true
                        bufferingSince = null
                        RtspHealth.report(camera, true)
                    }
                    Player.STATE_BUFFERING ->
                        if (bufferingSince == null) bufferingSince = System.currentTimeMillis()
                    else -> bufferingSince = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                fail()
            }

            fun fail() {
                if (failed) return
                failed = true
                // Remember per camera, not globally: a camera being off says nothing about the
                // others, and a global verdict would drop the whole fleet to go2rtc for the session.
                RtspHealth.report(camera, false)
                currentOnFail()
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Nothing rendered within the deadline: an unreachable camera can leave RTSP setup hanging
    // without ever raising an error, and a silent black frame is worse than a fast step-down.
    LaunchedEffect(url) {
        delay(READY_DEADLINE_MS)
        if (!ready && !failed) {
            failed = true
            RtspHealth.report(camera, false)
            currentOnFail()
        }
    }

    // Stalled long after it was working. The main stream is FIXED-bitrate (~5 Mbps) with no
    // adaptation, so a link that cannot carry it degrades to a stall rather than to lower quality.
    // go2rtc's WebRTC below does adapt, which makes stepping down the right move on weak cellular.
    LaunchedEffect(bufferingSince, failed) {
        val since = bufferingSince ?: return@LaunchedEffect
        if (failed) return@LaunchedEffect
        delay(STALL_TIMEOUT_MS - (System.currentTimeMillis() - since).coerceAtLeast(0L))
        if (bufferingSince == since && !failed) {
            failed = true
            // NOT reported to the breaker: the camera is fine, the network could not keep up. A
            // verdict here would wrongly disable the tier for the rest of the session.
            currentOnFail()
        }
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

/** RTSP SETUP/DESCRIBE deadline. Short: an unreachable camera should cost about a second. */
private const val CONNECT_TIMEOUT_MS = 4_000L

/** No first frame by this point, error or not, and the tier gives up. */
private const val READY_DEADLINE_MS = 5_000L

/** Continuous stall after playback started — the link cannot carry a fixed-bitrate main stream. */
private const val STALL_TIMEOUT_MS = 7_000L
