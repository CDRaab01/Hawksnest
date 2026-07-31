/**
 * Pure math for pinch-to-zoom on the camera picture — the Ring/Reolink gesture: pinch to magnify,
 * drag to pan around, double-tap to reset.
 *
 * Kept dependency-free and free of React/DOM so it can be unit-tested directly and ported 1:1 to
 * Kotlin in `core/logic/VideoZoom.kt`. The components are thin renderers over this: web applies
 * the result as a CSS `transform`, Android as a `graphicsLayer`. Both must agree, because "how far
 * can I pan at 3x" is exactly the kind of thing that silently diverges between two hand-written
 * gesture handlers.
 *
 * The state is a `{ scale, offsetX, offsetY }` transform about the CENTRE of the video frame,
 * offsets in pixels of the *unscaled* frame. Identity is `{ 1, 0, 0 }`.
 *
 * This is a VIEW transform only. It magnifies the pixels already on screen — it is not optical
 * zoom and does not touch the camera. The E1 Zoom's real optical zoom is PTZ, and lives in
 * `cameraPtz.ts`. A user pinching a 640x360 sub-stream is looking at bigger blurry pixels, which
 * is exactly what Ring does and what they asked for.
 */

/** Identity — no magnification, centred. */
export const NO_ZOOM: ZoomState = { scale: 1, offsetX: 0, offsetY: 0 };

/**
 * Most magnification allowed. 4x on a 640x360 sub-stream is already ~1 source pixel per 4 screen
 * pixels; past that the user is panning around a mush of interpolation and the gesture stops
 * feeling like it's doing anything useful.
 */
export const MAX_SCALE = 4;

/** Below this we snap back to exactly 1 — floating-point pinch residue would otherwise leave the
 *  frame imperceptibly scaled forever, keeping the "zoomed" affordances lit with nothing zoomed. */
const SNAP_EPSILON = 0.01;

export interface ZoomState {
  scale: number;
  offsetX: number;
  offsetY: number;
}

/** Frame size in CSS/dp pixels. */
export interface FrameSize {
  width: number;
  height: number;
}

/**
 * How far the content may be dragged from centre on each axis before an edge would come inside
 * the frame. At scale `s` the content is `w*s` wide inside a `w` window, so the slack either side
 * is `w*(s-1)/2`. At `s <= 1` there is no slack at all — hence the clamp to 0 rather than a
 * negative bound, which would invert the clamp and let the picture drift off screen.
 */
export function maxOffset(size: number, scale: number): number {
  return Math.max(0, (size * (scale - 1)) / 2);
}

/** Clamp a scale into [1, MAX_SCALE], snapping near-identity back to exactly 1. */
export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return 1;
  const s = Math.min(MAX_SCALE, Math.max(1, scale));
  return s < 1 + SNAP_EPSILON ? 1 : s;
}

/**
 * Normalise a zoom state: clamp the scale first, then re-clamp the offsets against the *new*
 * scale. Order matters — zooming back out has to pull the picture back towards centre, otherwise
 * an offset that was legal at 4x survives at 1.2x and leaves a black gap at the edge of frame.
 */
export function clampZoom(z: ZoomState, frame: FrameSize): ZoomState {
  const scale = clampScale(z.scale);
  const mx = maxOffset(frame.width, scale);
  const my = maxOffset(frame.height, scale);
  return {
    scale,
    offsetX: Math.min(mx, Math.max(-mx, Number.isFinite(z.offsetX) ? z.offsetX : 0)),
    offsetY: Math.min(my, Math.max(-my, Number.isFinite(z.offsetY) ? z.offsetY : 0)),
  };
}

/**
 * Apply one frame of a pinch/drag gesture.
 *
 * `focusX`/`focusY` are the gesture centroid **relative to the centre of the frame** — so a pinch
 * centred on the top-left corner of a 320x180 frame passes `(-160, -90)`. Zooming keeps the point
 * under the user's fingers pinned, which is the difference between a zoom that feels direct and
 * one that feels like it's fighting you: the content point under the centroid must stay put, so
 * the offset has to move by `focus * (1 - ratio)` where `ratio` is the achieved scale change.
 *
 * `ratio` is derived from the CLAMPED scales rather than the requested `scaleChange`, so that a
 * pinch which runs into the 1x or 4x limit stops translating too — otherwise the picture keeps
 * sliding under fingers that are no longer achieving any magnification.
 */
export function applyGesture(
  z: ZoomState,
  frame: FrameSize,
  gesture: { scaleChange: number; panX: number; panY: number; focusX: number; focusY: number },
): ZoomState {
  const change = Number.isFinite(gesture.scaleChange) && gesture.scaleChange > 0 ? gesture.scaleChange : 1;
  const nextScale = clampScale(z.scale * change);
  const ratio = z.scale === 0 ? 1 : nextScale / z.scale;
  return clampZoom(
    {
      scale: nextScale,
      offsetX: (z.offsetX + gesture.panX) + gesture.focusX * (1 - ratio),
      offsetY: (z.offsetY + gesture.panY) + gesture.focusY * (1 - ratio),
    },
    frame,
  );
}

/** True when the picture is magnified — drives the "Reset zoom" affordance and, on web, whether
 *  the frame should swallow drags instead of letting them reach the page. */
export function isZoomed(z: ZoomState): boolean {
  return z.scale > 1;
}

/**
 * A zoomed picture must capture drag gestures to pan, but an UNzoomed one must not — otherwise the
 * video swallows the vertical page scroll on a phone, which reads as the app being broken. This is
 * the one rule both platforms have to share.
 */
export function shouldCaptureDrag(z: ZoomState): boolean {
  return isZoomed(z);
}

/** CSS transform for the web renderer. Translate-then-scale about the centre. */
export function cssTransform(z: ZoomState): string {
  return `translate(${z.offsetX}px, ${z.offsetY}px) scale(${z.scale})`;
}
