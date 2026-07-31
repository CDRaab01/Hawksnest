package com.hawksnest.core.logic

/**
 * Pure math for pinch-to-zoom on the camera picture — the Ring/Reolink gesture: pinch to magnify,
 * drag to pan around, double-tap to reset.
 *
 * 1:1 port of `src/lib/videoZoom.ts`. Kept free of Compose so it can be unit-tested directly; the
 * composable is a thin renderer that feeds gestures in and applies the result as a `graphicsLayer`.
 * Both platforms must agree, because "how far can I pan at 3x" is exactly the kind of thing that
 * silently diverges between two hand-written gesture handlers.
 *
 * The state is a transform about the CENTRE of the video frame, offsets in pixels of the
 * *unscaled* frame. Identity is `ZoomState()`.
 *
 * This is a VIEW transform only. It magnifies the pixels already on screen — it is not optical
 * zoom and does not touch the camera. The E1 Zoom's real optical zoom is PTZ, and lives in
 * [CameraPtz]. A user pinching a 640x360 sub-stream is looking at bigger blurry pixels, which is
 * exactly what Ring does and what was asked for.
 */

/**
 * Most magnification allowed. 4x on a 640x360 sub-stream is already ~1 source pixel per 4 screen
 * pixels; past that the user is panning around a mush of interpolation and the gesture stops
 * feeling like it's doing anything useful.
 */
const val MAX_SCALE = 4f

/**
 * Below this we snap back to exactly 1 — floating-point pinch residue would otherwise leave the
 * frame imperceptibly scaled forever, keeping the "zoomed" affordances lit with nothing zoomed.
 */
private const val SNAP_EPSILON = 0.01f

data class ZoomState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Frame size in pixels. */
data class FrameSize(val width: Float, val height: Float)

/** Identity — no magnification, centred. */
val NO_ZOOM = ZoomState()

/**
 * How far the content may be dragged from centre on each axis before an edge would come inside the
 * frame. At scale `s` the content is `w*s` wide inside a `w` window, so the slack either side is
 * `w*(s-1)/2`. At `s <= 1` there is no slack at all — hence the coerce to 0 rather than a negative
 * bound, which would invert the clamp and let the picture drift off screen.
 */
fun maxOffset(size: Float, scale: Float): Float = ((size * (scale - 1f)) / 2f).coerceAtLeast(0f)

/** Clamp a scale into [1, MAX_SCALE], snapping near-identity back to exactly 1. */
fun clampScale(scale: Float): Float {
    if (!scale.isFinite()) return 1f
    val s = scale.coerceIn(1f, MAX_SCALE)
    return if (s < 1f + SNAP_EPSILON) 1f else s
}

/**
 * Normalise a zoom state: clamp the scale first, then re-clamp the offsets against the *new*
 * scale. Order matters — zooming back out has to pull the picture back towards centre, otherwise
 * an offset that was legal at 4x survives at 1.2x and leaves a black gap at the edge of frame.
 */
fun clampZoom(z: ZoomState, frame: FrameSize): ZoomState {
    val scale = clampScale(z.scale)
    val mx = maxOffset(frame.width, scale)
    val my = maxOffset(frame.height, scale)
    val ox = if (z.offsetX.isFinite()) z.offsetX else 0f
    val oy = if (z.offsetY.isFinite()) z.offsetY else 0f
    return ZoomState(scale, ox.coerceIn(-mx, mx), oy.coerceIn(-my, my))
}

/**
 * Apply one frame of a pinch/drag gesture.
 *
 * [focusX]/[focusY] are the gesture centroid **relative to the centre of the frame** — so a pinch
 * centred on the top-left corner of a 320x180 frame passes `(-160, -90)`. Zooming keeps the point
 * under the user's fingers pinned, which is the difference between a zoom that feels direct and
 * one that feels like it's fighting you: the content point under the centroid must stay put, so
 * the offset has to move by `focus * (1 - ratio)` where `ratio` is the achieved scale change.
 *
 * [scaleChange] is applied via the CLAMPED scales rather than as requested, so a pinch that runs
 * into the 1x or 4x limit stops translating too — otherwise the picture keeps sliding under
 * fingers that are no longer achieving any magnification.
 */
fun applyGesture(
    z: ZoomState,
    frame: FrameSize,
    scaleChange: Float,
    panX: Float,
    panY: Float,
    focusX: Float,
    focusY: Float,
): ZoomState {
    val change = if (scaleChange.isFinite() && scaleChange > 0f) scaleChange else 1f
    val nextScale = clampScale(z.scale * change)
    val ratio = if (z.scale == 0f) 1f else nextScale / z.scale
    return clampZoom(
        ZoomState(
            scale = nextScale,
            offsetX = (z.offsetX + panX) + focusX * (1f - ratio),
            offsetY = (z.offsetY + panY) + focusY * (1f - ratio),
        ),
        frame,
    )
}

/** True when the picture is magnified — drives the "Reset zoom" affordance. */
fun isZoomed(z: ZoomState): Boolean = z.scale > 1f

/**
 * A zoomed picture must capture drag gestures to pan, but an UNzoomed one must not — otherwise the
 * video swallows the vertical page scroll, which reads as the app being broken. This is the one
 * rule both platforms have to share.
 */
fun shouldCaptureDrag(z: ZoomState): Boolean = isZoomed(z)
