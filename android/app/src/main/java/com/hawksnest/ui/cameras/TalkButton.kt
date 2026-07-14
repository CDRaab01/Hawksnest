package com.hawksnest.ui.cameras

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hawksnest.ui.theme.HawksnestTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private enum class TalkState { IDLE, CONNECTING, TALKING, ERROR }

/**
 * Push-to-talk for a Ring camera over the dedicated go2rtc (native `ring:` source — see
 * hawksnest-automation §7c). Hold the button to capture the mic and stream it to the camera's
 * back-channel; release to end. Live video keeps playing on its own, so this is a sendonly-audio
 * peer connection. Signaling rides go2rtc's WebSocket API; media is direct to go2rtc's :8555.
 * Mirrors the web `TalkButton`. [src] is the go2rtc stream name (= HA camera base).
 */
@Composable
fun TalkButton(
    src: String,
    viewModel: CameraPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(TalkState.IDLE) }
    var session by remember { mutableStateOf<TalkSession?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    fun startTalk() {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (session != null) return
        val base = viewModel.baseUrl()
        if (base.isEmpty()) {
            state = TalkState.ERROR
            return
        }
        state = TalkState.CONNECTING
        session = TalkSession(context, base, src) { state = it }.also { it.start() }
    }

    fun stopTalk() {
        session?.close()
        session = null
        if (state != TalkState.ERROR) state = TalkState.IDLE
    }

    DisposableEffect(Unit) { onDispose { session?.close() } }

    val pulse = HawksnestTheme.pulse
    val active = state == TalkState.TALKING || state == TalkState.CONNECTING
    val bg = when {
        active -> pulse.recovery
        state == TalkState.ERROR -> pulse.streakDim
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        active -> Color.White
        state == TalkState.ERROR -> pulse.streak
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state) {
        TalkState.TALKING -> "Talking…"
        TalkState.CONNECTING -> "Connecting…"
        TalkState.ERROR -> "Talk failed"
        TalkState.IDLE -> "Hold to talk"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .pointerInput(src) {
                detectTapGestures(
                    onPress = {
                        startTalk()
                        tryAwaitRelease()
                        stopTalk()
                    },
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (hasPermission) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * One push-to-talk negotiation: open the go2rtc WebSocket, offer a sendonly-audio peer connection,
 * apply the answer + trickle ICE, and stream the mic. Lives outside composition so org.webrtc's
 * worker-thread callbacks can mutate it under a lock. Mirrors the web TalkButton's session.
 */
private class TalkSession(
    context: Context,
    baseUrl: String,
    src: String,
    private val onState: (TalkState) -> Unit,
) {
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var audioSource: AudioSource? = null
    private val httpClient = OkHttpClient()
    private val wsUrl = go2rtcWsUrl(baseUrl, src)
    private val lock = Any()
    private var closed = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun start() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peer = factory.createPeerConnection(config, observer) ?: run { fail(); return }
        pc = peer
        // Capture the mic and send it only (we don't need the camera's media back here).
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory.createAudioTrack("mic", source)
        peer.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        )
        ws = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), wsListener)
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val peer = pc ?: return
            peer.createOffer(
                object : SimpleSdp() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peer.setLocalDescription(SimpleSdp(), desc)
                        send("webrtc/offer", desc.description)
                    }

                    override fun onCreateFailure(error: String?) = fail()
                },
                MediaConstraints(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val peer = pc ?: return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "webrtc/answer" -> {
                    val sdp = msg.optString("value")
                    if (sdp.isNotEmpty()) {
                        peer.setRemoteDescription(
                            SimpleSdp(),
                            SessionDescription(SessionDescription.Type.ANSWER, sdp),
                        )
                    }
                }
                "webrtc/candidate" -> {
                    val cand = msg.optString("value")
                    if (cand.isNotEmpty()) peer.addIceCandidate(IceCandidate("0", 0, cand))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail()
    }

    private val observer = object : SimplePeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) {
            send("webrtc/candidate", candidate.sdp)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onState(TalkState.TALKING)
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> fail()
                else -> Unit
            }
        }
    }

    private fun send(type: String, value: String) {
        ws?.send(JSONObject().put("type", type).put("value", value).toString())
    }

    private fun fail() {
        synchronized(lock) {
            if (closed) return
        }
        onState(TalkState.ERROR)
        close()
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { ws?.close(1000, null) }
        ws = null
        runCatching { pc?.close() }
        pc = null
        runCatching { audioSource?.dispose() }
        audioSource = null
    }
}

/** No-op SdpObserver base so callers override only what they need. */
private open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

/** No-op PeerConnection.Observer base so callers override only what they need. */
private open class SimplePeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    override fun onConnectionChange(p0: PeerConnection.PeerConnectionState) {}
}
