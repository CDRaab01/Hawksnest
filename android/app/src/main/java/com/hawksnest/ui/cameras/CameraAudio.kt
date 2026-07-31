package com.hawksnest.ui.cameras

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService

/**
 * How a camera's sound behaves on the phone — the Ring/Reolink stance rather than a phone call's.
 *
 * Three separate things had to be true and only the first is about the player itself:
 *
 *  1. **Playback must be MEDIA, not a call.** That is fixed in [WebRtcCore], which configures the
 *     audio device module's attributes. Without it none of the below matters, because Android
 *     routes a `USAGE_VOICE_COMMUNICATION` stream by call rules no matter who asks for focus.
 *  2. **The volume rocker must adjust the right slider** — [CameraVolumeKeys].
 *  3. **Other apps' audio must be interrupted only when there is actually something to hear** —
 *     [CameraAudioFocus].
 */

/**
 * Point the hardware volume keys at the media stream while a camera is on screen.
 *
 * Android decides which slider the rocker moves from the activity's `volumeControlStream`, and
 * the default (`USE_DEFAULT_STREAM_TYPE`) means "whatever is currently playing" — which, with
 * nothing audible yet, is the ringer. So pressing volume-up on a silent camera changed the RINGER
 * volume, and then unmuting played at some unrelated level. Pinning it to `STREAM_MUSIC` makes the
 * rocker mean the same thing before and after unmuting.
 *
 * Restored on the way out so the rest of the app keeps the system default.
 */
@Composable
fun CameraVolumeKeys() {
    // Not `as? Activity` — see findActivity in ZoomableFrame.kt. Compose's LocalContext is often a
    // ContextThemeWrapper, and the failed cast silently no-ops instead of erroring.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val previous = activity.volumeControlStream
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { activity.volumeControlStream = previous }
    }
}

/**
 * Hold audio focus only while the camera is actually making sound.
 *
 * Every player mounts muted, so opening a camera must not touch anyone else's audio — that was the
 * complaint. But once sound is deliberately turned on, playing over the top of a podcast is just as
 * wrong in the other direction, so unmuting takes transient focus and muting gives it straight
 * back. Requesting focus on mount, or holding it for the whole session, would both reproduce the
 * original bug in a quieter form.
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` rather than a full gain: a camera is a glance, not a
 * listening session. Music dips and recovers by itself instead of being stopped, which is what
 * Ring does and what makes checking a camera feel cheap.
 *
 * Losing focus is deliberately NOT handled by muting the player. The remote track keeps running
 * and the system ducks it for us; a real interruption (a phone call) pauses the whole app anyway.
 * Reacting to transient loss by flipping the caller's mute state would fight the user's own
 * button and leave the UI disagreeing with what they last pressed.
 */
@Composable
fun CameraAudioFocus(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active, context) {
        val manager = context.getSystemService<AudioManager>()
        if (!active || manager == null) return@DisposableEffect onDispose {}

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            // We do not pause on transient loss (see above), so there is nothing for a listener
            // to do; declining one keeps the system from holding a callback into a dead composable.
            .setWillPauseWhenDucked(false)
            .build()

        manager.requestAudioFocus(request)
        onDispose { manager.abandonAudioFocusRequest(request) }
    }
}
