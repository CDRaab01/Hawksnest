import type { FootageSpan } from "./ringFootage";
import type { TimeWindow } from "./timelineViewport";

/**
 * Pure math for **clip export**: turning a scrub position into a start/end range that Frigate can
 * actually cut, and deciding whether that range is exportable at all.
 *
 * Kept dependency-free and free of React/DOM so it can be unit-tested directly and ported 1:1 to
 * Kotlin in `core/logic/ClipExport.kt`. Both platforms' selection UIs are thin renderers over
 * this — no clamping, no duration rules, and no filename shaping may live in a component, because
 * that is exactly the kind of thing that drifts between web and Android invisibly.
 *
 * The backend is Frigate's `clip.mp4`, reached through the HA Frigate integration's
 * `RecordingProxyView` (`/api/frigate/recording/<camera>/start/<s>/end/<e>`). It runs an ffmpeg
 * concat over the 60-second recording segments with integer-second `inpoint`/`outpoint`, which is
 * why {@link clipRangeSeconds} rounds *outwards* — see there.
 */

/**
 * Shortest exportable clip.
 *
 * The cut is `-c copy`, which cannot slice mid-GOP: ffmpeg starts at the first keyframe at or after
 * `inpoint`, and the in/out points are whole seconds. Under about five seconds a request can come
 * back with a single keyframe — or nothing — and that reads as a bug rather than as a short clip.
 */
export const MIN_CLIP_MS = 5_000;

/**
 * Longest exportable clip (10 minutes, ~150–300 MB at these cameras' bitrates).
 *
 * Not an arbitrary tidiness limit. Frigate generates the file on demand and streams it, so the
 * whole run holds an ffmpeg process *and* an HA proxy connection open; the response has no
 * `Content-Length`, so neither platform can show real progress while it does. Ten minutes is long
 * enough for any incident worth keeping and short enough that "nothing is happening yet" never
 * lasts long enough to read as a hang.
 */
export const MAX_CLIP_MS = 10 * 60_000;

/** Half-width of the selection seeded when clip mode opens (playhead ±15s). */
export const DEFAULT_HALF_MS = 15_000;

/**
 * Touch tolerance for grabbing a selection handle, in pixels.
 *
 * Shared by both platforms so a handle that is grabbable on the web is grabbable on the phone. The
 * drawn handle is a hairline; this is the invisible target around it.
 */
export const HANDLE_HIT_SLOP_PX = 20;

/** Fine adjust step. One second is ffmpeg's own granularity — anything smaller would be a lie. */
export const NUDGE_FINE_MS = 1_000;

/**
 * Coarse adjust step. At the timeline's opening 1-hour zoom this is about 1.5 px, which is
 * precisely the range dragging cannot address — so the two instruments don't overlap.
 */
export const NUDGE_COARSE_MS = 15_000;

/**
 * How far back from *now* the exportable range has to stop.
 *
 * Frigate writes 60-second recording segments, and the one currently being written is not in the
 * recordings table yet — so `clip.mp4` cannot see it and a selection running up to "now" comes back
 * short or 400s. Fails closed by one full segment.
 */
export const EXPORT_TAIL_LAG_MS = 60_000;

/**
 * Slop when deciding whether a range is fully covered — edge rounding only, not gap bridging.
 *
 * It is tempting to reuse `ringFootage`'s 15 s span tolerance here so the validator and the lane
 * agree. That would be wrong twice over: the spans handed to {@link coverage} have *already* been
 * coalesced with that tolerance, so small holes are invisible by the time they arrive, and
 * re-applying 15 s would let a 30-second selection with half its footage missing report as
 * complete. Agreement with the lane comes from measuring the same spans, not from copying its
 * constant.
 */
const COVERAGE_TOLERANCE_MS = 1_000;

/** An export range, in epoch ms. Always `startMs <= endMs`. */
export interface ClipSelection {
  startMs: number;
  endMs: number;
}

/** Which end of the selection an interaction is moving. */
export type ClipEdge = "start" | "end";

/**
 * How much of the selection has recorded footage behind it.
 *
 * `"unknown"` is not a failure mode — it means the footage lane is empty, which happens when the
 * lookup failed or the source doesn't provide one. Coverage deliberately **fails open** there:
 * blocking an export because we couldn't ask is worse than letting Frigate answer for itself.
 * (Distinct from `frigate.ts`, which fails *closed* on camera identification. Different question:
 * "is this a Frigate camera" must never guess yes; "is there footage here" may.)
 */
export type ClipCoverage = "full" | "partial" | "none" | "unknown";

/** Why a selection cannot be exported. Null from {@link selectionProblem} means it can. */
export type ClipProblem = "too-short" | "too-long" | "no-footage";

/** Duration of a selection in ms. */
export function selectionDurationMs(sel: ClipSelection): number {
  return sel.endMs - sel.startMs;
}

/**
 * The exportable range inside `window`: never past retention, never into the not-yet-recorded.
 *
 * Two separate ceilings collapse into one here. The timeline's window is padded past *now* so the
 * "Live" region can render, so it alone would let a selection be dragged into the future; and even
 * "now" is too far, because the segment currently being written is not yet queryable
 * (see {@link EXPORT_TAIL_LAG_MS}).
 *
 * Callers must pass a **live** `nowMs`, not the pinned `nowAnchor` the timeline uses for its
 * layout: a player left open for hours would otherwise keep offering a start time that Frigate has
 * since rotated out of retention.
 */
export function exportBounds(window: TimeWindow, nowMs: number): TimeWindow {
  const startMs = window.startMs;
  const endMs = Math.max(startMs, Math.min(window.endMs, nowMs - EXPORT_TAIL_LAG_MS));
  return { startMs, endMs };
}

/**
 * Normalise a selection: inside the window, ordered, and within the duration limits.
 *
 * `anchor` is the edge the user just set — it stays put and the *other* edge yields. Without it,
 * dragging the end handle would appear to move the start, because every duration fix would be
 * applied to the same end of the range.
 */
export function clampSelection(
  sel: ClipSelection,
  bounds: TimeWindow,
  anchor: ClipEdge = "start",
): ClipSelection {
  const lo = bounds.startMs;
  const hi = Math.max(lo, bounds.endMs);
  const available = hi - lo;
  // A range narrower than the minimum clip has exactly one answer: all of it.
  if (available <= MIN_CLIP_MS) return { startMs: lo, endMs: hi };

  // Resolve the duration FIRST, then place it. Clamping the two edges independently and fixing the
  // duration afterwards looks equivalent and is not: a selection overhanging the end of the range
  // gets its far edge pulled in, and the "fix" then reads that shortened span as the intent. A
  // 30-second clip marked at the live edge silently became 15 seconds that way.
  const requested = Math.max(0, sel.endMs - sel.startMs);
  const duration = Math.min(available, Math.max(MIN_CLIP_MS, Math.min(MAX_CLIP_MS, requested)));

  // Then slide — never shrink — to fit, keeping the edge the user is holding where they put it.
  if (anchor === "start") {
    const startMs = Math.min(Math.max(sel.startMs, lo), hi - duration);
    return { startMs, endMs: startMs + duration };
  }
  const endMs = Math.max(Math.min(sel.endMs, hi), lo + duration);
  return { startMs: endMs - duration, endMs };
}

/** The selection clip mode opens with: `DEFAULT_HALF_MS` either side of the playhead. */
export function defaultSelection(playheadMs: number, bounds: TimeWindow): ClipSelection {
  return clampSelection(
    { startMs: playheadMs - DEFAULT_HALF_MS, endMs: playheadMs + DEFAULT_HALF_MS },
    bounds,
  );
}

/**
 * Move one edge to `ms` — the "Start here"/"End here" buttons and the drag handles.
 *
 * The moved edge is authoritative: if honouring it would break the duration limits, the opposite
 * edge moves out of the way. That is what makes "mark in, then mark out" work in either order.
 */
export function setEdge(
  sel: ClipSelection,
  edge: ClipEdge,
  ms: number,
  bounds: TimeWindow,
): ClipSelection {
  const next =
    edge === "start" ? { startMs: ms, endMs: sel.endMs } : { startMs: sel.startMs, endMs: ms };
  return clampSelection(next, bounds, edge);
}

/** Shift one edge by `deltaMs` — the ±1s / ±15s fine-adjust buttons. */
export function nudge(
  sel: ClipSelection,
  edge: ClipEdge,
  deltaMs: number,
  bounds: TimeWindow,
): ClipSelection {
  const from = edge === "start" ? sel.startMs : sel.endMs;
  return setEdge(sel, edge, from + deltaMs, bounds);
}

/**
 * Which handle a press at `xPx` grabbed, or null for a press on neither.
 *
 * Takes **already-projected pixel positions** rather than a viewport, so the same function serves
 * the DOM and Compose without either platform's geometry types leaking in here. Ties go to `"end"`:
 * when the two handles are on top of each other the selection is at its minimum, and the edge the
 * user can usefully move is the one that grows it.
 */
export function pickHandle(
  xPx: number,
  startXPx: number,
  endXPx: number,
  slopPx: number = HANDLE_HIT_SLOP_PX,
): ClipEdge | null {
  const dStart = Math.abs(xPx - startXPx);
  const dEnd = Math.abs(xPx - endXPx);
  if (dStart > slopPx && dEnd > slopPx) return null;
  if (dEnd > slopPx) return "start";
  if (dStart > slopPx) return "end";
  return dStart < dEnd ? "start" : "end";
}

/**
 * How much of the selection is backed by recorded footage.
 *
 * The spans come from `frigate/recordings/get` (coalesced by `footageSpans`), which reads the same
 * `Recordings` table Frigate's own `clip.mp4` queries — so this answers, before we ask, the
 * question Frigate would otherwise answer with a 400. Only `playable` spans count: a span that
 * exists but cannot be decoded produces no video.
 */
export function coverage(sel: ClipSelection, spans: FootageSpan[]): ClipCoverage {
  if (spans.length === 0) return "unknown";
  const duration = selectionDurationMs(sel);
  if (duration <= 0) return "none";

  let covered = 0;
  for (const span of spans) {
    if (!span.playable) continue;
    const overlap = Math.min(sel.endMs, span.endMs) - Math.max(sel.startMs, span.startMs);
    if (overlap > 0) covered += overlap;
  }

  if (covered <= 0) return "none";
  return covered >= duration - COVERAGE_TOLERANCE_MS ? "full" : "partial";
}

/**
 * Why this selection can't be exported, or null if it can.
 *
 * Partial coverage is deliberately **not** a problem — Frigate concatenates whatever exists, so the
 * export still succeeds and just comes back shorter. That is a warning for the UI to show (via
 * {@link coverage}), not a reason to refuse.
 */
export function selectionProblem(sel: ClipSelection, spans: FootageSpan[]): ClipProblem | null {
  const duration = selectionDurationMs(sel);
  if (duration < MIN_CLIP_MS) return "too-short";
  if (duration > MAX_CLIP_MS) return "too-long";
  if (coverage(sel, spans) === "none") return "no-footage";
  return null;
}

/**
 * The integer-second range to put in the export URL.
 *
 * Rounded **outwards** (floor the start, ceil the end) on purpose: ffmpeg's `inpoint`/`outpoint`
 * are whole seconds, so rounding inwards would hand back a clip fractionally shorter than the one
 * the user marked — and the frame they were aiming at is exactly the one at the edge.
 */
export function clipRangeSeconds(sel: ClipSelection): { startSec: number; endSec: number } {
  return {
    startSec: Math.floor(sel.startMs / 1000),
    endSec: Math.ceil(sel.endMs / 1000),
  };
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

/**
 * Download filename: `kitchen-2026-08-09-10-42-15-50s.mp4`.
 *
 * Local time, not ISO/UTC — the timestamp has to match what the timeline showed when the user
 * marked the clip, or the file is unfindable later. (Same reasoning as the Android snapshot save,
 * which also stamps local time.)
 */
export function clipFileName(cameraName: string, sel: ClipSelection): string {
  const d = new Date(sel.startMs);
  const stamp =
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `-${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`;
  const seconds = Math.max(1, Math.round(selectionDurationMs(sel) / 1000));
  const safeName = cameraName.replace(/[^a-zA-Z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "camera";
  return `${safeName}-${stamp}-${seconds}s.mp4`;
}
