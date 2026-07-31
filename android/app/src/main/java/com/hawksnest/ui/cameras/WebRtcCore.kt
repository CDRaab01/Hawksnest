package com.hawksnest.ui.cameras

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

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
            // Watching a camera is MEDIA, not a phone call.
            //
            // libwebrtc's default audio device module builds its AudioTrack with
            // USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH (verified by decompiling
            // WebRtcAudioTrack.getAudioAttributes in stream-webrtc-android 1.3.10: `setUsage(2)`,
            // `setContentType(1)`). Android treats that as a call, which is why opening a camera
            // hijacked the phone's audio: it interrupted whatever was playing, moved playback onto
            // the IN-CALL volume slider so the volume rocker adjusted the wrong thing, and could
            // route to the earpiece instead of the speaker.
            //
            // It happened even though every player mounts muted, because the mute gate only
            // disables the received track (`setMuted`) — the AudioTrack is created and started by
            // the ADM as soon as the audio transceiver negotiates, long before anything is
            // audible. Muting could therefore never have fixed it.
            //
            // USAGE_MEDIA + CONTENT_TYPE_MOVIE puts camera sound where a video's sound belongs:
            // the media stream, at the media volume, out of the speaker, mixing with the system's
            // normal focus rules instead of pre-empting them.
            //
            // This is playout only. Two-way talk (`TalkButton`) captures the mic through this same
            // factory and is unaffected — the ADM's recording side has its own configuration, and
            // the mic is only opened when a local audio track actually exists.
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context.applicationContext)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .createAudioDeviceModule(),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        inited = true
    }
}
