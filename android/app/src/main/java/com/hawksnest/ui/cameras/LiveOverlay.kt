package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared "coming up" chrome for the two WebRTC live tiers ([Go2rtcPlayer], [WebRtcPlayer]).
 *
 * Both players used to carry their own copy of this overlay and their own first-frame wiring, and
 * both carried the same bug in it — so it now lives in one place.
 */

/**
 * Reports the first video frame of **one session**.
 *
 * ## Why the renderer cannot do this job
 *
 * `SurfaceViewRenderer` offers `RendererEvents.onFirstFrameRendered()`, which is what both players
 * used. It is a **latch**: `EglRenderer` sets `firstFrameRendered` on its first frame and only ever
 * clears it in `init()`. The renderer, meanwhile, is held in an *unkeyed* `remember` — deliberately,
 * because it must outlive each stream (recreating the SurfaceView per stream flickers, and freeing
 * it while a peer still renders into it crashes natively; see the dispose-order note in each
 * player). So the renderer is `init()`ed once per player mount and spans every stream that player
 * shows.
 *
 * Each new stream, however, re-arms the overlay (`DisposableEffect(src) { connecting = true }`).
 * The first stream cleared it; the second and every one after set it with nothing left that could
 * ever clear it — an **opaque** black scrim pinned over perfectly good video, behind a green "Live"
 * badge. Reproduced on the Reolink doorbell: after a Low/High toggle, libwebrtc reported ~20 fps
 * received and ~10 fps rendered continuously for 70+ seconds while the screen stayed black, and
 * `Reporting first rendered frame` appeared exactly once in the whole logcat.
 *
 * A sink attached to the *track* is scoped to the session by construction, so every stream gets its
 * own first-frame signal no matter how many have run before it on the same renderer.
 *
 * [onFirstFrame] is invoked on a libwebrtc thread — hop to the main scope before touching state.
 */
class FirstFrameSink(private val onFirstFrame: () -> Unit) : VideoSink {

    private val fired = AtomicBoolean(false)

    /**
     * The latch itself, separated from [onFrame] so it is testable without a real [VideoFrame]
     * (constructing one needs a native-backed buffer). Fires [onFirstFrame] at most once.
     */
    fun markFrame() {
        if (fired.compareAndSet(false, true)) onFirstFrame()
    }

    override fun onFrame(frame: VideoFrame) = markFrame()
}

/**
 * The "Connecting…" scrim, shown until the session's first frame arrives.
 *
 * **Deliberately translucent.** It used to be `Color.Black`, which meant a stuck `connecting` flag
 * was indistinguishable from a dead camera and hid a working picture completely. The flag bug above
 * is fixed, but the scrim stays see-through as the standing defence: if anything ever pins this
 * again, the user sees the video through it rather than a black rectangle. Same lesson as the
 * go2rtc breaker deadlock — a transport that has silently degraded must not look like one that is
 * simply still working.
 */
@Composable
fun ConnectingOverlay(
    modifier: Modifier = Modifier,
    /** "Connecting…" for a camera that answers in a second; "Waking camera…" for one that sleeps. */
    label: String = "Connecting…",
) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Dark enough to read the spinner against a bright frame, sheer enough to prove video is flowing. */
private const val SCRIM_ALPHA = 0.6f
