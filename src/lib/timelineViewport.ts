/**
 * Pure math for the Ring-style camera timeline: a center-anchored, zoomable +
 * pannable viewport over a fixed data window. Kept dependency-free and free of
 * React/DOM so it can be unit-tested directly (and ported 1:1 to Kotlin in
 * `core/logic/TimelineViewport.kt`). The component is a thin renderer over this.
 *
 * A viewport is `{ centerMs, msPerPx }` for a track of pixel width `W`:
 *  - `centerMs` is the time under the fixed center playhead (the scrub position),
 *  - `msPerPx` is the zoom scale (milliseconds per pixel; smaller = more zoomed in).
 */

export const MINUTE_MS = 60_000;
export const HOUR_MS = 3_600_000;
/** Most zoomed-IN visible span (Ring-style ~10 min). */
export const MIN_SPAN_MS = 10 * MINUTE_MS;
/** Most zoomed-OUT visible span ceiling (the rolling data window is usually 24h). */
export const MAX_SPAN_MS = 24 * HOUR_MS;

/** The fixed data window the viewport lives inside (epoch ms). */
export interface TimeWindow {
  startMs: number;
  endMs: number;
}

export interface Viewport {
  centerMs: number;
  msPerPx: number;
}

/** Visible span (ms) for a track of width `width` px. */
export function visibleSpanMs(vp: Viewport, width: number): number {
  return vp.msPerPx * width;
}

/** Map a time to its x pixel (center-anchored: centerMs → W/2). */
export function timeToX(t: number, vp: Viewport, width: number): number {
  return width / 2 + (t - vp.centerMs) / vp.msPerPx;
}

/** Map an x pixel back to a time. Inverse of `timeToX`. */
export function xToTime(x: number, vp: Viewport, width: number): number {
  return vp.centerMs + (x - width / 2) * vp.msPerPx;
}

/** Largest visible span allowed: min(24h, the data window itself). */
export function maxSpanMs(window: TimeWindow): number {
  return Math.min(MAX_SPAN_MS, Math.max(1, window.endMs - window.startMs));
}

/** Smallest visible span allowed (collapses if the window is itself < MIN_SPAN). */
export function minSpanMs(window: TimeWindow): number {
  return Math.min(MIN_SPAN_MS, maxSpanMs(window));
}

/** Clamp the zoom so the visible span stays within [minSpan, maxSpan]. */
export function clampMsPerPx(msPerPx: number, width: number, window: TimeWindow): number {
  if (width <= 0) return msPerPx;
  const lo = minSpanMs(window) / width;
  const hi = maxSpanMs(window) / width;
  return Math.min(hi, Math.max(lo, msPerPx));
}

/**
 * Clamp the center so the visible window stays inside the data window. When the
 * data window is narrower than the visible span, pin to the window midpoint.
 */
export function clampCenter(
  centerMs: number,
  width: number,
  msPerPx: number,
  window: TimeWindow,
): number {
  const half = (msPerPx * width) / 2;
  const lo = window.startMs + half;
  const hi = window.endMs - half;
  if (lo > hi) return (window.startMs + window.endMs) / 2;
  return Math.min(hi, Math.max(lo, centerMs));
}

/** Normalize a viewport: clamp zoom first, then recenter within bounds. */
export function clampViewport(vp: Viewport, width: number, window: TimeWindow): Viewport {
  const msPerPx = clampMsPerPx(vp.msPerPx, width, window);
  const centerMs = clampCenter(vp.centerMs, width, msPerPx, window);
  return { centerMs, msPerPx };
}

/** Pan by a pixel delta (drag dx). Drag the strip right (dx > 0) → go back in time. */
export function pan(vp: Viewport, dxPx: number, width: number, window: TimeWindow): Viewport {
  return clampViewport({ ...vp, centerMs: vp.centerMs - dxPx * vp.msPerPx }, width, window);
}

/** Zoom about the center by `factor` (> 1 zooms in; centerMs unchanged). */
export function zoom(vp: Viewport, factor: number, width: number, window: TimeWindow): Viewport {
  const f = factor > 0 ? factor : 1;
  return clampViewport({ ...vp, msPerPx: vp.msPerPx / f }, width, window);
}

/** Build a viewport showing `span` ms centered at `centerMs`, clamped to the window. */
export function viewportForSpan(
  centerMs: number,
  span: number,
  width: number,
  window: TimeWindow,
): Viewport {
  const msPerPx = width > 0 ? span / width : 1;
  return clampViewport({ centerMs, msPerPx }, width, window);
}

/** The visible [startMs, endMs] of the viewport. */
export function visibleRange(vp: Viewport, width: number): TimeWindow {
  const half = (vp.msPerPx * width) / 2;
  return { startMs: vp.centerMs - half, endMs: vp.centerMs + half };
}

// "Nice" tick intervals, coarsest-last; picked so ~targetTicks land on screen.
const TICK_STEPS = [
  5 * MINUTE_MS,
  15 * MINUTE_MS,
  30 * MINUTE_MS,
  HOUR_MS,
  3 * HOUR_MS,
  6 * HOUR_MS,
  12 * HOUR_MS,
];

/** Pick a tick interval (ms) for the given visible span. */
export function tickIntervalMs(spanMs: number, targetTicks = 6): number {
  const ideal = spanMs / Math.max(1, targetTicks);
  for (const step of TICK_STEPS) if (step >= ideal) return step;
  return TICK_STEPS[TICK_STEPS.length - 1];
}

/** Tick times (epoch ms) falling within the viewport's visible range. */
export function ticks(vp: Viewport, width: number): number[] {
  const { startMs, endMs } = visibleRange(vp, width);
  const interval = tickIntervalMs(endMs - startMs);
  const first = Math.ceil(startMs / interval) * interval;
  const out: number[] = [];
  for (let t = first; t <= endMs; t += interval) out.push(t);
  return out;
}
