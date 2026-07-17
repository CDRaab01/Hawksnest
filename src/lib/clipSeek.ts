import type { CameraEvent } from "./cameraEvents";

/**
 * Timeline-time → clip mapping for live scrub-to-preview. Ring event clips arrive
 * with `endMs: null` (ring-mqtt's selector doesn't carry a duration) — the real
 * span is only known once the HLS media loads, so containment takes the loaded
 * clip's media duration as an input and assumes a short span until then.
 *
 * Pure + unit-tested; has a 1:1 Kotlin port in `core/logic/ClipSeek.kt`.
 */

/** Span assumed for a clip whose real duration isn't known yet (matches the
 *  timeline's minimum chip width assumption for open-ended events). */
export const ASSUMED_CLIP_SPAN_MS = 30_000;

/**
 * The effective end of `e` on the timeline: its real `endMs` when known, else
 * the loaded media duration when `e` is the clip currently loaded in the
 * player, else a conservative {@link ASSUMED_CLIP_SPAN_MS}.
 */
export function clipSpanEndMs(
  e: CameraEvent,
  loadedClipId: string | null,
  loadedDurationMs: number | null,
): number {
  if (e.endMs !== null) return e.endMs;
  if (loadedClipId === e.id && loadedDurationMs !== null && loadedDurationMs > 0) {
    return e.startMs + loadedDurationMs;
  }
  return e.startMs + ASSUMED_CLIP_SPAN_MS;
}

/**
 * The clip whose `[startMs, spanEnd]` contains `t`, or null when `t` falls in a
 * gap. On overlap the latest-starting clip wins (deterministic, so the player's
 * clip-keyed effects can't thrash between two ids at a boundary).
 */
export function clipContaining(
  events: CameraEvent[],
  t: number,
  loadedClipId: string | null,
  loadedDurationMs: number | null,
): CameraEvent | null {
  let best: CameraEvent | null = null;
  for (const e of events) {
    if (t < e.startMs || t > clipSpanEndMs(e, loadedClipId, loadedDurationMs)) continue;
    if (!best || e.startMs >= best.startMs) best = e;
  }
  return best;
}

/** Offset of `t` within clip `e`, clamped ≥ 0, in seconds (the player seeks in seconds). */
export function offsetInClipSeconds(e: CameraEvent, t: number): number {
  return Math.max(0, (t - e.startMs) / 1000);
}
