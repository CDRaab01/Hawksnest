package com.hawksnest.ui.cameras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Stop a stream the moment the activity is STOPPED, and bring it back on the next START.
 *
 * Every camera transport used to live exactly as long as its composable, on the theory that
 * leaving the player unmounts it. But a STOPPED activity keeps its composition: pressing power or
 * letting the screen time out with a camera open unmounts nothing, so the stream kept pulling
 * video — and libwebrtc's AudioTrack, which plays from negotiation even while muted (see
 * [WebRtcCore]), kept holding an AudioMix wakelock — with the screen off, for as long as the
 * process lived. With push enabled the foreground service makes that "indefinitely".
 *
 * [onStop] runs **synchronously inside the lifecycle callback**, and that is the point of this
 * helper. Flipping a state and letting recomposition dispose the player cannot work here: Compose
 * pauses the frame clock at ON_STOP, so nothing recomposes until the activity is started again —
 * which is precisely the window the stream must not be running in. [onRestart] has no such
 * problem (it runs at ON_START, when the clock is back), so it may simply bump a key.
 *
 * Picture-in-picture is unaffected: a PiP activity is PAUSED, not stopped, so minimizing a live
 * camera keeps streaming. Turning the screen off *while* in PiP does stop it, and should.
 *
 * [onRestart] only fires after a real [onStop] — `addObserver` replays ON_START to a new observer,
 * and that replay must not restart a stream that was never stopped.
 */
@Composable
internal fun StopWhileBackgrounded(onStop: () -> Unit, onRestart: () -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnRestart by rememberUpdatedState(onRestart)
    DisposableEffect(lifecycle) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    currentOnStop()
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    currentOnRestart()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
