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
    /** The media's real (width, height), so the frame around this tier takes the picture's shape
     *  rather than a hardcoded 16:9 — see `core/logic/MediaAspect.kt`. */
    onVideoSize: ((Int, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentOnFail by rememberUpdatedState(onFail)
    val currentOnVideoSize by rememberUpdatedState(onVideoSize)
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

            // Rotation-corrected the same way the WebRTC renderers do it, so a rotated source
            // reports the shape the picture is actually drawn in.
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val swap = videoSize.unappliedRotationDegrees % 180 != 0
                val w = if (swap) videoSize.height else videoSize.width
                val h = if (swap) videoSize.width else videoSize.height
                if (w > 0 && h > 0) currentOnVideoSize?.invoke(w, h)
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
    // Screen off / app backgrounded: drop the RTSP session (a fixed ~5 Mbps main stream that was
    // otherwise pulled behind a dark screen) and re-prepare on return. See StopWhileBackgrounded.
    // `everStopped` keeps the ready-deadline below from reading "stopped before the first frame"
    // as "camera unreachable" and condemning the camera in RtspHealth; after a restart the stall
    // timer covers a camera that really has gone away.
    var everStopped by remember(url) { mutableStateOf(false) }
    StopWhileBackgrounded(
        onStop = {
            everStopped = true
            player.stop()
        },
        onRestart = { player.prepare() },
    )

    LaunchedEffect(url) {
        delay(READY_DEADLINE_MS)
        if (!ready && !failed && !everStopped) {
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
        // `factory` runs once per view, but `player` is rebuilt whenever the url changes. Without
        // this the view stayed bound to the released player: its last frame frozen on screen,
        // the new stream connected and invisible. Same guard VideoPlayer has.
        update = { view -> if (view.player !== player) view.player = player },
    )
}

/** RTSP SETUP/DESCRIBE deadline. Short: an unreachable camera should cost about a second. */
private const val CONNECT_TIMEOUT_MS = 4_000L

/**
 * No first frame by this point, error or not, and the tier gives up. Seven seconds, not five: the
 * slowest measured in-app first frame was 4.0 s and the camera side alone reached 4.8 s in probes
 * (the new viewer waits for the next keyframe, one every 2 s). Missing the deadline is expensive,
 * since the relay then starts from scratch, so it has to clear the slow tail, not the median.
 */
private const val READY_DEADLINE_MS = 7_000L

/** Continuous stall after playback started — the link cannot carry a fixed-bitrate main stream. */
private const val STALL_TIMEOUT_MS = 7_000L
