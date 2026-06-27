package com.hawksnest.ui.cameras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hawksnest.core.ha.WebRtcHandle
import com.hawksnest.core.ha.WebRtcSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
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
    viewModel: CameraPlayerViewModel,
    onFail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep the step-down callback current without restarting the negotiation each recomposition.
    val currentOnFail = rememberUpdatedState(onFail)
    val eglBase = remember { EglBase.create() }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
        }
    }

    DisposableEffect(entityId) {
        val session = WebRtcSession(scope, viewModel, eglBase, renderer, entityId) {
            currentOnFail.value()
        }
        session.start()
        onDispose { session.close() }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.release()
            eglBase.release()
        }
    }

    AndroidView(factory = { renderer }, modifier = modifier)
}

/**
 * Drives one negotiation: createOffer → send to HA → apply answer + trickle ICE → render the track.
 * Lives outside composition so org.webrtc's worker-thread callbacks can mutate it under a lock.
 */
private class WebRtcSession(
    private val scope: CoroutineScope,
    private val viewModel: CameraPlayerViewModel,
    eglBase: EglBase,
    private val renderer: SurfaceViewRenderer,
    private val entityId: String,
    private val onFail: () -> Unit,
) {
    private val factory: PeerConnectionFactory
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

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(renderer.context.applicationContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

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

        // Fall back if we haven't connected within 10s rather than hanging on a black frame.
        watchdog = scope.launch {
            delay(10_000)
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

    /** Negotiation failed — report once so the parent steps down to HLS/MJPEG, then tear down. */
    private fun fail() {
        val report = synchronized(lock) { !closed }
        if (!report) return
        scope.launch { onFail() }
        close()
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        watchdog?.cancel()
        runCatching { handle?.close() }
        runCatching { peer?.dispose() }
        runCatching { factory.dispose() }
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
