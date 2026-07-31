package com.hawksnest.ui.cameras

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hawksnest.core.logic.FrameSize
import com.hawksnest.core.logic.NO_ZOOM
import com.hawksnest.core.logic.ZoomState
import com.hawksnest.core.logic.applyGesture

/**
 * Pinch-to-zoom + drag-to-pan over the camera picture, the way Ring and the Reolink app do it.
 *
 * Wraps the WHOLE player ladder rather than any one tier. Every tier — RTSP, go2rtc WebRTC, HA
 * WebRTC, HLS, MJPEG, snapshot, recorded VOD — already renders into one shared `frame` modifier,
 * so zooming the container gets all seven for free and cannot drift between them. Zooming inside
 * each player would have meant seven implementations and seven ways to be subtly different.
 *
 * All the clamping math is in `core/logic/VideoZoom.kt` (ported 1:1 from `lib/videoZoom.ts`) and
 * unit-tested there; this composable only turns gestures into calls and the result into a
 * `graphicsLayer`.
 *
 * The magnification is a VIEW transform — bigger pixels, not a sharper image, and it does not move
 * the camera. Real optical zoom on the E1 Zoom is PTZ, which is the separate Move control.
 */
@Composable
fun ZoomableFrame(
    zoom: ZoomState,
    onZoomChange: (ZoomState) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Modifier) -> Unit,
) {
    // The gesture lambdas are installed once (`pointerInput(Unit)`) so a re-pinch doesn't restart
    // the gesture detector mid-gesture; these keep them reading the CURRENT zoom and callback
    // rather than the ones captured on first composition.
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnChange by rememberUpdatedState(onZoomChange)

    Box(
        modifier
            // Without this the magnified picture paints over the controls above and below it.
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = true) { centroid, pan, gestureZoom, _ ->
                    val frame = FrameSize(size.width.toFloat(), size.height.toFloat())
                    currentOnChange(
                        applyGesture(
                            currentZoom,
                            frame,
                            scaleChange = gestureZoom,
                            panX = pan.x,
                            panY = pan.y,
                            // The math wants the centroid relative to the CENTRE of the frame;
                            // Compose reports it relative to the top-left.
                            focusX = centroid.x - frame.width / 2f,
                            focusY = centroid.y - frame.height / 2f,
                        ),
                    )
                }
            }
            .pointerInput(Unit) {
                // Double-tap to reset. The universal escape hatch for a zoom gesture: without it a
                // user who has panned into a corner has to pinch their way back out by feel.
                detectTapGestures(onDoubleTap = { currentOnChange(NO_ZOOM) })
            },
    ) {
        content(
            Modifier.graphicsLayer {
                scaleX = currentZoom.scale
                scaleY = currentZoom.scale
                translationX = currentZoom.offsetX
                translationY = currentZoom.offsetY
            },
        )
    }
}

/**
 * Immersive landscape while [active]. Ring and Reolink both do this, and a 16:9 frame on a tall
 * phone is otherwise letterboxed into a third of the screen.
 *
 * Restores the previous orientation and the system bars on exit AND on disposal — leaving the app
 * locked to landscape because the user backed out of a camera would be a nasty little bug.
 *
 * MainActivity declares `configChanges` for orientation, so this rotation does NOT recreate the
 * activity: the player keeps its connection instead of tearing down and re-negotiating, which is
 * a 2-4 second reconnect on the WebRTC tiers.
 */
@Composable
fun FullscreenEffect(active: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

    DisposableEffect(active, activity) {
        if (!active || activity == null) return@DisposableEffect onDispose {}

        val controller = WindowInsetsControllerCompat(activity.window, view)
        val previousOrientation = activity.requestedOrientation

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity.requestedOrientation = previousOrientation
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
