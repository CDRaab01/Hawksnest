package com.hawksnest.ui.cameras

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Process-wide WebRTC singletons (EGL context + PeerConnectionFactory), created once and never torn
 * down.
 *
 * Why this exists: `PeerConnectionFactory.initialize()` is documented to run exactly once per
 * process, and the factory owns libwebrtc's signaling/worker/network threads (shared by every
 * PeerConnection it makes). The original player created **and disposed** a factory on every camera
 * open/close. Disposing it destroys those threads and their mutexes — and if an in-flight callback
 * is still running on the signaling thread, it then locks an already-destroyed mutex and the whole
 * process aborts:
 *
 *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
 *   Fatal signal 6 (SIGABRT) in tid … (signaling_threa) … libjingle_peerconnection_so.so
 *
 * (It only started crashing once WebRTC actually began being used — before that the player was
 * gated to HLS and this path never ran.) Keeping one factory + one EglBase alive for the process
 * removes the teardown race entirely; only the per-view PeerConnection and SurfaceViewRenderer are
 * disposed.
 */
object WebRtcCore {
    private var inited = false

    lateinit var eglBase: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        inited = true
    }
}
