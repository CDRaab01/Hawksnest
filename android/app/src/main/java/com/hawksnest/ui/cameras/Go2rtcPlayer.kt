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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnFail = rememberUpdatedState(onFail)
    remember(context) { WebRtcCore.init(context) }
    val connecting = remember { mutableStateOf(true) }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(
                WebRtcCore.eglBase.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() { scope.launch { connecting.value = false } }
                    override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {}
                },
            )
            setEnableHardwareScaler(true)
        }
    }

    // LIFO dispose order (see WebRtcPlayer): the renderer release is declared BEFORE the session so
    // the PeerConnection is torn down first and stops feeding frames before the surface is freed.
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }

    DisposableEffect(src) {
        connecting.value = true
        val session = Go2rtcSession(scope, baseUrl, src, WebRtcCore.factory, renderer) {
            currentOnFail.value()
        }
        session.start()
        onDispose { session.close() }
    }

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
private class Go2rtcSession(
    private val scope: CoroutineScope,
    baseUrl: String,
    src: String,
    private val factory: PeerConnectionFactory,
    private val renderer: SurfaceViewRenderer,
    private val onFail: () -> Unit,
) {
    private val wsUrl = go2rtcWsUrl(baseUrl, src)
    private val httpClient = OkHttpClient()
    private var peer: PeerConnection? = null
    private var ws: WebSocket? = null
    private var watchdog: Job? = null
    private val lock = Any()
    private var closed = false

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

        // go2rtc-direct is meant to be fast; step down after 8s rather than hang. The "Connecting…"
        // overlay covers the wait; a stale stream / unreachable :8555 media both land here.
        watchdog = scope.launch {
            delay(8_000)
            if (peer?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) fail()
        }
    }

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
            (transceiver.receiver?.track() as? VideoTrack)?.addSink(renderer)
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.addSink(renderer)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> Go2rtcHealth.report(true)
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> fail()
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
    private fun fail() {
        val report = synchronized(lock) { !closed }
        if (!report) return
        Go2rtcHealth.report(false)
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
