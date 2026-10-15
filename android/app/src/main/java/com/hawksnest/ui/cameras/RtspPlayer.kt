package com.hawksnest.ui.cameras

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.exoplayer.DefaultLoadControl
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
    /** A still of this camera for the ~2 s the camera takes to send anything — see [LivePoster]. */
    poster: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentOnFail by rememberUpdatedState(onFail)
    val currentOnVideoSize by rememberUpdatedState(onVideoSize)
    var ready by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    // The first frame actually on screen. Clears the connecting overlay and satisfies the deadline
    // below; READY alone could arrive a little later (it also waits for the start buffer).
    var firstFrame by remember(url) { mutableStateOf(false) }
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
            // ExoPlayer's stock buffering is for files and VOD: hold 1 s of media before playing,
            // 2 s after any stall (media3 1.11). A camera's RTSP feed arrives in real time, so each
            // of those seconds is a second of a frozen or missing picture — and of permanent lag,
            // since a live feed never runs ahead to refill it. Play the moment frames decode; come
            // back from a Wi-Fi blip in well under a second.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        LIVE_MIN_BUFFER_MS,
                        LIVE_MAX_BUFFER_MS,
                        START_BUFFER_MS,
                        REBUFFER_MS,
                    )
                    .build(),
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

            override fun onRenderedFirstFrame() {
                firstFrame = true
                RtspHealth.report(camera, true)
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
        if (!ready && !firstFrame && !failed && !everStopped) {
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

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
        // This tier used to show nothing at all until the first frame: PlayerView's black shutter
        // for the ~2 s a Reolink takes to answer a new RTSP viewer — measured on the owner's phone
        // as a black box at a 2.4 s median, against the vendor app's still at 0.4 s. The same
        // overlay the WebRTC tiers use, with the camera's still under it.
        if (!firstFrame && !failed) ConnectingOverlay(poster = poster)
    }
}

/** RTSP SETUP/DESCRIBE deadline. Short: an unreachable camera should cost about a second. */
private const val CONNECT_TIMEOUT_MS = 4_000L

/**
 * No first frame by this point, error or not, and the tier gives up.
 *
 * Five seconds (it was seven). Measured 2026-10-14: a Reolink sends a new RTSP viewer its first
 * packet ~2.0–2.2 s after the request (main stream, 24 cold connects across four cameras, worst
 * 2.75 s), and the phone showed a first frame at 2.0–3.4 s — while still holding ExoPlayer's 1 s
 * start buffer, which [START_BUFFER_MS] removes. Five seconds still clears that tail by ~2 s.
 * The seven-second figure dated from that buffer (September's slowest in-app frame, 4.0 s,
 * included it), and it was paid in full every time this tier failed: one Garage open in eleven
 * missed it and showed nothing for 11.5 s. A failover now keeps the camera's still on screen
 * (see [ConnectingOverlay]), so stepping down early costs a second, not a black screen.
 */
private const val READY_DEADLINE_MS = 5_000L

/** Play as soon as a quarter-second is decoded — enough to ride out one late packet, no more. */
private const val START_BUFFER_MS = 250

/** After a stall, half a second buffered and go again (ExoPlayer's default is 2 s). */
private const val REBUFFER_MS = 500

/**
 * Keep loading while under this much. A live feed can only ever be as far ahead as the network
 * jitter, so these bound nothing in practice — they only have to satisfy DefaultLoadControl's
 * ordering (min >= both play thresholds, max >= min).
 */
private const val LIVE_MIN_BUFFER_MS = 2_000
private const val LIVE_MAX_BUFFER_MS = 5_000

/** Continuous stall after playback started — the link cannot carry a fixed-bitrate main stream. */
private const val STALL_TIMEOUT_MS = 7_000L
