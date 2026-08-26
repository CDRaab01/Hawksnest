package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hawksnest.core.net.Go2rtcHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Lowest-latency live view: WebRTC negotiated **directly with the dedicated go2rtc** (native Ring
 * source) over its WebSocket API (`/go2rtc/api/ws?src=<base>`, proxied same-origin by nginx) — the
 * same signaling the Talk button uses, but recvonly. go2rtc talks straight to Ring with no
 * ring-mqtt/ffmpeg hop, so first frame is typically ~1–2 s vs the HA path's ~5–15 s on battery.
 * Media is WebRTC to go2rtc's `:8555`.
 *
 * On any failure — WS error, no answer, ICE can't reach `:8555` (e.g. the §7c host forwarder isn't
 * up, or off-tailnet) — it calls [onFail] so `CameraPlayer` steps down to the HA WebRTC path, and
 * trips [Go2rtcHealth] so other cameras skip this tier. Renders + captures frames exactly like
 * [WebRtcPlayer] (shared [WebRtcCore] EGL/factory, [LiveFrameStore] tile capture, "Connecting…"
 * overlay).
 */
@Composable
fun Go2rtcPlayer(
    src: String,
    /** Logical camera id — key under which captured live frames are stashed for the grid tile. */
    cameraId: String,
    baseUrl: String,
    onFail: () -> Unit,
    /** Audio gate: the received AudioTrack is disabled while true. Defaults muted —
     *  org.webrtc plays remote audio automatically, which is the web twin's opposite
     *  default; the player chrome's MuteButton is the deliberate way to sound. */
    muted: Boolean = true,
    /** Reports the source video's (width, height) — post-rotation — when known/changed. Feeds
     *  the PiP window's aspect ratio. */
    onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnFail = rememberUpdatedState(onFail)
    // Kept current without recreating the renderer (same reason as currentOnFail — the renderer
    // lives in an unkeyed remember).
    val currentOnVideoSize = rememberUpdatedState(onVideoSize)
    remember(context) { WebRtcCore.init(context) }
    val connecting = remember { mutableStateOf(true) }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(
                WebRtcCore.eglBase.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() { scope.launch { connecting.value = false } }
                    override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                        // WebRTC reports pre-rotation dimensions; swap for portrait rotations so
                        // the consumer sees the shape actually rendered. Posted to the main
                        // scope — this fires on a libwebrtc thread.
                        val (rw, rh) = if (rotation % 180 == 0) w to h else h to w
                        scope.launch { currentOnVideoSize.value?.invoke(rw, rh) }
                    }
                },
            )
            setEnableHardwareScaler(true)
            // Show the WHOLE frame; do not crop to fill.
            //
            // SurfaceViewRenderer defaults to SCALE_ASPECT_BALANCED, which crops when the
            // container's aspect differs from the source. In the portrait 16:9 box they match, so
            // it looks right — but fullscreen rotates to a 19.5:9 landscape screen, and BALANCED
            // then eats the top and bottom of a 16:9 picture. Reported as "full mode cuts off the
            // camera... looks zoomed in", and there is nothing to pan back into view because the
            // crop is the renderer's, not the zoom's.
            //
            // FIT pillarboxes instead. Black bars beside the picture are the correct answer for a
            // security camera: seeing all of the frame beats filling all of the glass.
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }

    // LIFO dispose order (see WebRtcPlayer): the renderer release is declared BEFORE the session so
    // the PeerConnection is torn down first and stops feeding frames before the surface is freed.
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }

    val session = remember { mutableStateOf<Go2rtcSession?>(null) }
    DisposableEffect(src) {
        connecting.value = true
        val s = Go2rtcSession(scope, baseUrl, src, WebRtcCore.factory, renderer, muted) {
            currentOnFail.value()
        }
        session.value = s
        s.start()
        onDispose {
            session.value = null
            s.close()
        }
    }
    LaunchedEffect(muted, session.value) { session.value?.setMuted(muted) }

    // Stash the live frame periodically so the grid tile shows what you were just watching (same as
    // WebRtcPlayer; captureFrame waits for a real frame, so nothing is stored while "Connecting…").
    LaunchedEffect(src, cameraId) {
        while (true) {
            captureFrame(renderer)?.let { LiveFrameStore.put(cameraId, it, System.currentTimeMillis()) }
            delay(3_000)
        }
    }

    Box(modifier) {
        AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize())
        if (connecting.value) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Connecting…",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * One recvonly negotiation against go2rtc: open the WS, offer a recvonly video+audio peer
 * connection, apply the answer + trickle ICE, render the incoming track. Lives outside composition
 * so org.webrtc's worker-thread callbacks can mutate it under a lock. Signaling mirrors the Talk
 * session; rendering mirrors WebRtcSession.
 */
/**
 * How long to wait for CONNECTED before stepping down. go2rtc-direct exists because it is fast
 * (~1-2 s to first frame); a longer wait would sit on the "Connecting…" overlay instead of trying
 * the tier below, and every failure here also costs a global verdict via [Go2rtcHealth].
 */
private const val WATCHDOG_MS = 8_000L

private class Go2rtcSession(
    private val scope: CoroutineScope,
    baseUrl: String,
    src: String,
    private val factory: PeerConnectionFactory,
    private val renderer: SurfaceViewRenderer,
    initialMuted: Boolean,
    private val onFail: () -> Unit,
) {
    private val wsUrl = go2rtcWsUrl(baseUrl, src)
    private val httpClient = OkHttpClient()
    private var peer: PeerConnection? = null
    private var ws: WebSocket? = null
    private var watchdog: Job? = null
    private val lock = Any()
    private var closed = false

    // The received audio track, gated by the chrome's MuteButton. Volatile: the
    // track lands on org.webrtc's worker thread, setMuted comes from composition.
    @Volatile private var audioTrack: AudioTrack? = null

    @Volatile private var muted = initialMuted

    /** Enable/disable the remote audio track (org.webrtc plays it automatically otherwise). */
    fun setMuted(m: Boolean) {
        muted = m
        runCatching { audioTrack?.setEnabled(!m) }
    }

    fun start() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, observer) ?: run { fail(); return }
        peer = pc
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        ws = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), wsListener)

        // go2rtc-direct is meant to be fast; step down rather than hang. The "Connecting…"
        // overlay covers the wait; a stale stream / unreachable :8555 media both land here.
        watchdog = scope.launch {
            delay(WATCHDOG_MS)
            if (peer?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) fail()
        }
    }

    /**
     * Whether this session ever reached CONNECTED. Gates whether a later failure is allowed to
     * report a GLOBAL verdict to [Go2rtcHealth] — see onConnectionChange.
     */
    @Volatile
    private var everConnected = false

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val pc = peer ?: return
            pc.createOffer(
                object : Go2rtcSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(Go2rtcSdpObserver(), desc)
                        send("webrtc/offer", desc.description)
                    }

                    override fun onCreateFailure(error: String?) = fail()
                },
                MediaConstraints(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val pc = peer ?: return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "webrtc/answer" -> {
                    val sdp = msg.optString("value")
                    if (sdp.isNotEmpty()) {
                        pc.setRemoteDescription(
                            Go2rtcSdpObserver(),
                            SessionDescription(SessionDescription.Type.ANSWER, sdp),
                        )
                    }
                }
                "webrtc/candidate" -> {
                    val cand = msg.optString("value")
                    if (cand.isNotEmpty()) pc.addIceCandidate(IceCandidate("0", 0, cand))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail()
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            send("webrtc/candidate", candidate.sdp)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver?.track()) {
                is VideoTrack -> track.addSink(renderer)
                is AudioTrack -> {
                    audioTrack = track
                    runCatching { track.setEnabled(!muted) }
                }
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            when (val track = receiver.track()) {
                is VideoTrack -> track.addSink(renderer)
                is AudioTrack -> {
                    audioTrack = track
                    runCatching { track.setEnabled(!muted) }
                }
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    everConnected = true
                    Go2rtcHealth.report(true)
                }
                // A drop AFTER this session was connected is not evidence about go2rtc's media
                // path — we just proved that path works. It's a network blip, and DISCONNECTED in
                // particular is often transient and recoverable. Stepping this session down is
                // right; condemning the tier for every other camera is not, and used to be
                // permanent (see Go2rtcHealth). So only a session that NEVER connected reports a
                // global verdict.
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> fail(global = !everConnected)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun send(type: String, value: String) {
        ws?.send(JSONObject().put("type", type).put("value", value).toString())
    }

    /**
     * Negotiation failed — report the media path unhealthy (circuit-breaker) and step down.
     * Deliberately does NOT dispose the peer here: fail() can fire on libwebrtc's own signaling
     * thread, and tearing down from that thread is unsafe. The parent drops this player and close()
     * runs from onDispose on the main thread.
     */
    private fun fail(global: Boolean = true) {
        val report = synchronized(lock) { !closed }
        if (!report) return
        if (global) Go2rtcHealth.report(false)
        watchdog?.cancel()
        scope.launch { onFail() }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        watchdog?.cancel()
        runCatching { ws?.close(1000, null) }
        ws = null
        // Dispose only this view's PeerConnection — NEVER the shared WebRtcCore factory.
        runCatching { peer?.dispose() }
        peer = null
    }
}

/** SdpObserver with no-op defaults so call sites override only what they need. */
private open class Go2rtcSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
