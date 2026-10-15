package com.hawksnest.ui.cameras

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.LivePosterSource
import com.hawksnest.core.logic.livePosterSource
import kotlinx.coroutines.delay
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared "coming up" chrome for the live tiers ([RtspPlayer], [Go2rtcPlayer], [WebRtcPlayer]).
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
    /**
     * A still of this camera to show under the scrim while it connects ([LivePoster]) — what the
     * Reolink app does, and why it feels instant: a picture is up in ~0.4 s and the stream replaces
     * it. Without one the wait is a black box, which reads as a dead camera.
     *
     * It is drawn ABOVE the video surface (a SurfaceView punches through anything beneath it), so
     * it is the one opaque thing this overlay can put over a working picture. The standing defence
     * above therefore needs a second rule: the poster drops away after [POSTER_MAX_MS] whatever the
     * flag says, leaving only the see-through scrim. Every tier's own deadline is shorter than that
     * except a sleeping camera's wake, which then waits on the scrim as it always did.
     */
    poster: (@Composable () -> Unit)? = null,
) {
    var posterExpired by remember { mutableStateOf(false) }
    if (poster != null) {
        LaunchedEffect(Unit) {
            delay(POSTER_MAX_MS)
            posterExpired = true
        }
    }
    val showPoster = poster != null && !posterExpired
    Box(modifier.fillMaxSize()) {
        if (showPoster) poster?.invoke()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (showPoster) POSTER_SCRIM_ALPHA else SCRIM_ALPHA)),
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
}

/**
 * The still for the connecting wait, chosen by [livePosterSource]: the frame grabbed the last time
 * this camera played live, else its snapshot (Frigate's latest frame), else nothing.
 *
 * Reads [LiveFrameStore] HERE rather than in the player: the store is Compose state that a playing
 * WebRTC tier writes every 3 s, and a read in `CameraPlayer` would recompose the whole player —
 * and hitch its video — on every write. Read here, only this still recomposes, and only while the
 * overlay is up.
 */
@Composable
fun LivePoster(cameraId: String, snapshotUrl: String?, sleeps: Boolean) {
    val frame = LiveFrameStore.get(cameraId)
    val now = remember(cameraId) { System.currentTimeMillis() }
    when (livePosterSource(frame?.capturedAtMs, now, sleeps, snapshotUrl != null)) {
        LivePosterSource.FRAME -> Image(
            bitmap = frame!!.bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // Bucketed, not bare: Coil caches by URL, so the bare snapshot URL could paint whatever it
        // held from an hour ago. A 30 s bucket fetches a current frame yet lets a quick re-open
        // reuse the one it just loaded.
        LivePosterSource.SNAPSHOT -> CameraSnapshot(
            model = bustCache(snapshotUrl, now / POSTER_SNAPSHOT_BUCKET_MS),
            modifier = Modifier.fillMaxSize(),
        )
        LivePosterSource.NONE -> Unit
    }
}

/** Dark enough to read the spinner against a bright frame, sheer enough to prove video is flowing. */
private const val SCRIM_ALPHA = 0.6f

/** Over a poster: the still should read as the camera, the spinner as "not live yet". */
private const val POSTER_SCRIM_ALPHA = 0.4f

/**
 * The longest a poster may cover the frame. Longer than RTSP-direct's 5 s first-frame deadline plus
 * a relay start (~3–4 s) so a failover keeps its picture; far shorter than "forever".
 */
internal const val POSTER_MAX_MS = 12_000L

private const val POSTER_SNAPSHOT_BUCKET_MS = 30_000L
