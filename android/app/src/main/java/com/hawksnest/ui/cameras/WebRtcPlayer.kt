package com.hawksnest.ui.cameras

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hawksnest.core.ha.WebRtcHandle
import com.hawksnest.core.ha.WebRtcSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.webrtc.EglRenderer
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
 * Low-latency live view over WebRTC, negotiated through HA's `camera/webrtc/offer` (served by
 * go2rtc — ring-mqtt's streaming backend). A native `PeerConnection` receives the camera's media
 * into a [SurfaceViewRenderer]; the SDP answer + trickle ICE flow back over the HA WebSocket. On any
 * failure it calls [onFail] so the parent steps down to HLS/MJPEG/snapshot. Media is UDP and
 * connects directly to go2rtc via ICE (it does not traverse nginx) — fine on a LAN; off-LAN falls
 * back. Direct port of the web `WebRtcPlayer`.
 */
@Composable
fun WebRtcPlayer(
    entityId: String,
    /** Logical camera id — the key under which captured live frames are stashed for the grid tile. */
    cameraId: String,
    viewModel: CameraPlayerViewModel,
    onFail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep the step-down callback current without restarting the negotiation each recomposition.
    val currentOnFail = rememberUpdatedState(onFail)
    // Process-wide EGL context + PeerConnectionFactory, created once and never disposed (WebRtcCore).
    remember(context) { WebRtcCore.init(context) }
    // True until the first video frame actually renders. Drives the "Connecting…" overlay so the user
    // sees progress instead of a black void while the stream comes up — which can take several seconds,
    // especially on battery cameras that have to wake from sleep.
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

    // Declaration order is load-bearing. Compose disposes effects in reverse (LIFO), so the renderer
    // release MUST be declared *before* the session: that way, when this player leaves composition
    // (scrubbing live→recorded, switching cameras, closing the lightbox), the PeerConnection is
    // disposed FIRST and stops feeding frames, and only then is the renderer surface freed. Releasing
    // the renderer while the peer still renders into it crashes natively. The shared EglBase is NOT
    // released here — it lives for the process, so there's no EGL-teardown race at all.
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }

    DisposableEffect(entityId) {
        connecting.value = true // re-show "Connecting…" for the newly-selected camera
        val session = WebRtcSession(scope, viewModel, WebRtcCore.factory, renderer, entityId) {
            currentOnFail.value()
        }
        session.start()
        onDispose { session.close() }
    }

    // While the live view is on screen, grab the actual rendered frame periodically and stash the
    // latest into LiveFrameStore, so returning to the grid shows the tile you were just watching
    // instead of ring-mqtt's stale interval snapshot. captureFrame uses WebRTC's own GL readback
    // (addFrameListener) — NOT PixelCopy, which returns black when the video sits on a hardware
    // overlay (that's why battery-cam tiles never updated). It naturally waits for the first real
    // frame, so nothing is stored while "Connecting…". Cancelled on dispose, which removes the
    // pending listener before the renderer is released.
    LaunchedEffect(entityId, cameraId) {
        while (true) {
            captureFrame(renderer)?.let { LiveFrameStore.put(cameraId, it) }
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
 * Grab the next rendered video frame as an [ImageBitmap] via WebRTC's own GL readback
 * ([SurfaceViewRenderer.addFrameListener]) — reliable even when the SurfaceView composites through a
 * hardware overlay (where PixelCopy just returns black). The listener is one-shot (EglRenderer clears
 * it after firing), so this resolves on the next frame; while the stream is still coming up it simply
 * waits, so we never stash a blank tile. Returns null on a black frame or if the listener can't be
 * added. Cancelling the caller removes the pending listener before the renderer is released.
 */
private suspend fun captureFrame(renderer: SurfaceViewRenderer): ImageBitmap? {
    var listener: EglRenderer.FrameListener? = null
    try {
        return suspendCancellableCoroutine { cont ->
            val l = EglRenderer.FrameListener { bitmap ->
                if (cont.isActive) cont.resume(bitmap?.takeUnless { it.isMostlyBlack() }?.asImageBitmap())
            }
            listener = l
            runCatching { renderer.addFrameListener(l, 0.5f) }
                .onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { runCatching { renderer.removeFrameListener(l) } }
        }
    } finally {
        // Always detach (idempotent) — EglRenderer's listeners are one-shot, but don't rely on it.
        listener?.let { runCatching { renderer.removeFrameListener(it) } }
    }
}

/** Cheap "is this still the pre-connect black frame?" check — sample a 5×5 grid, treat near-zero
 *  luma everywhere as black so we don't promote an empty frame to the tile. */
private fun Bitmap.isMostlyBlack(): Boolean {
    var lit = 0
    for (i in 1..5) for (j in 1..5) {
        val px = getPixel(width * i / 6, height * j / 6)
        if (((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) > 24) lit++
    }
    return lit == 0
}

/**
 * Drives one negotiation: createOffer → send to HA → apply answer + trickle ICE → render the track.
 * Lives outside composition so org.webrtc's worker-thread callbacks can mutate it under a lock.
 */
private class WebRtcSession(
    private val scope: CoroutineScope,
    private val viewModel: CameraPlayerViewModel,
    private val factory: PeerConnectionFactory,
    private val renderer: SurfaceViewRenderer,
    private val entityId: String,
    private val onFail: () -> Unit,
) {
    private var peer: PeerConnection? = null
    private var handle: WebRtcHandle? = null
    private var watchdog: Job? = null

    private val lock = Any()
    private var sessionId: String? = null
    private var remoteSet = false
    private var closed = false
    /** Local ICE gathered before HA returned a session id (can't be tagged/sent yet). */
    private val pendingLocal = mutableListOf<IceCandidate>()
    /** Remote ICE that arrived before the answer was applied (can't be added yet). */
    private val pendingRemote = mutableListOf<IceCandidate>()

    fun start() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, observer) ?: run { fail(); return }
        peer = pc
        // We only receive media (recvonly), mirroring the web player's transceivers.
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                scope.launch {
                    try {
                        val h = viewModel.webrtcOffer(entityId, desc.description) { handleSignal(it) }
                        if (h == null) fail() else synchronized(lock) { if (closed) h.close() else handle = h }
                    } catch (_: Exception) {
                        fail()
                    }
                }
            }

            override fun onCreateFailure(error: String?) = fail()
        }, MediaConstraints())

        // Fall back if we haven't connected in time rather than hanging on a black frame. 20s (not
        // 10) because a battery camera has to wake from sleep and start its stream, which routinely
        // takes longer than 10s — cutting over to HLS too early just trades one black screen for a
        // slower one. The "Connecting…" overlay covers the wait.
        watchdog = scope.launch {
            delay(20_000)
            if (peer?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) fail()
        }
    }

    private fun handleSignal(signal: WebRtcSignal) {
        val pc = peer ?: return
        when (signal) {
            is WebRtcSignal.Session -> {
                val flush: List<IceCandidate>
                synchronized(lock) {
                    sessionId = signal.sessionId
                    flush = pendingLocal.toList().also { pendingLocal.clear() }
                }
                flush.forEach { sendLocalCandidate(it) }
            }
            is WebRtcSignal.Answer -> {
                pc.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            val flush: List<IceCandidate>
                            synchronized(lock) {
                                remoteSet = true
                                flush = pendingRemote.toList().also { pendingRemote.clear() }
                            }
                            flush.forEach { pc.addIceCandidate(it) }
                        }

                        override fun onSetFailure(error: String?) = fail()
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, signal.sdp),
                )
            }
            is WebRtcSignal.Candidate -> {
                val ice = IceCandidate(signal.sdpMid ?: "0", signal.sdpMLineIndex, signal.candidate)
                synchronized(lock) {
                    if (remoteSet) pc.addIceCandidate(ice) else pendingRemote.add(ice)
                }
            }
            WebRtcSignal.Error -> fail()
        }
    }

    private fun sendLocalCandidate(c: IceCandidate) {
        val sid = synchronized(lock) { sessionId } ?: return
        scope.launch {
            runCatching { viewModel.webrtcCandidate(entityId, sid, c.sdp, c.sdpMid, c.sdpMLineIndex) }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val ready = synchronized(lock) {
                if (sessionId == null) { pendingLocal.add(candidate); false } else true
            }
            if (ready) sendLocalCandidate(candidate)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver?.track() as? VideoTrack)?.addSink(renderer)
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.addSink(renderer)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> fail()
                else -> Unit
            }
        }

        // Unused callbacks (required by the interface).
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

    /**
     * Negotiation failed — report so the parent steps down to HLS/MJPEG. Deliberately does NOT
     * dispose the peer here: fail() can fire on libwebrtc's own signaling thread (e.g. from
     * onConnectionChange), and tearing the PeerConnection down from its callback thread is unsafe.
     * The parent drops this player from composition, and close() then runs from onDispose on the
     * main thread.
     */
    private fun fail() {
        val report = synchronized(lock) { !closed }
        if (!report) return
        watchdog?.cancel()
        scope.launch { onFail() }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        watchdog?.cancel()
        runCatching { handle?.close() }
        // Dispose only this view's PeerConnection — NEVER the factory (WebRtcCore owns it for the
        // whole process). Disposing the factory per session is exactly what aborted the app with
        // "pthread_mutex_lock on a destroyed mutex" on the signaling thread.
        runCatching { peer?.dispose() }
        peer = null
    }
}

/** SdpObserver with no-op defaults so call sites override only what they need. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
