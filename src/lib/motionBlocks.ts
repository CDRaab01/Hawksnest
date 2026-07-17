import type { HistoryPoint } from "../store/source";
import type { CameraEvent } from "./cameraEvents";

/**
 * "Moments of action" for the Ring-style timeline, recovered from a motion/ding **binary_sensor's**
 * state history. ring-mqtt's event selector only surfaces the last ~5 *playable* clips, so on its
 * own the timeline reads nearly empty; HA's recorder, however, has the whole day of motion on/off
 * transitions. This folds that history into duration blocks so the day fills with activity the way
 * Ring's does — then `mergePlayable` marks the handful that still have a recording.
 *
 * Pure + exhaustively unit-tested; has a 1:1 Kotlin port in `core/logic/SensorBlocks.kt`.
 */

const MOTION_ON = "on";

/** Default coalescing gap: motion pulses within a minute of each other read as one "moment" (Ring
 *  groups nearby motion into a single event rather than a picket fence of flickers). */
export const DEFAULT_MERGE_GAP_MS = 60_000;

interface Run {
  start: number;
  end: number | null;
}

/**
 * Fold a binary_sensor's history `points` (`{t, state}`) into duration `CameraEvent` blocks: a
 * block spans each "on" run, and runs separated by a gap shorter than `mergeGapMs` coalesce into
 * one. A run still "on" at the end of history is left open (`endMs = null`, "ongoing"). Blocks
 * carry `hasClip: false` — they are markers; playable clips are overlaid separately by
 * `mergePlayable`. Non-"on" states (`off`/`unavailable`/`unknown`) all count as "not active".
 * Returned oldest-first.
 */
export function motionBlocksFromHistory(
  points: HistoryPoint[],
  cameraName: string,
  label = "motion",
  mergeGapMs: number = DEFAULT_MERGE_GAP_MS,
): CameraEvent[] {
  if (points.length === 0) return [];
  const sorted = [...points].sort((a, b) => a.t - b.t);

  // Raw on-runs: [start, end?] where end === null means "still on at the end of history".
  const runs: Run[] = [];
  let openStart: number | null = null;
  for (const p of sorted) {
    const on = p.state.toLowerCase() === MOTION_ON;
    if (on && openStart === null) {
      openStart = p.t;
    } else if (!on && openStart !== null) {
      runs.push({ start: openStart, end: p.t });
      openStart = null;
    }
  }
  if (openStart !== null) runs.push({ start: openStart, end: null });
  if (runs.length === 0) return [];

  // Coalesce runs whose off-gap is under mergeGapMs (an ongoing run can only be the last).
  const merged: Run[] = [];
  for (const r of runs) {
    const last = merged[merged.length - 1];
    if (last && last.end !== null && r.start - last.end < mergeGapMs) {
      last.end = r.end;
    } else {
      merged.push({ start: r.start, end: r.end });
    }
  }

  return merged.map((r) => ({
    id: `${label}:${r.start}`,
    camera: cameraName,
    label,
    startMs: r.start,
    endMs: r.end,
    hasClip: false,
    hasSnapshot: false,
    thumbnailUrl: null,
    snapshotUrl: null,
  }));
}

/**
 * Overlay the playable ring clips (`playable` — the real `Motion N` handles at their true times)
 * onto the motion `blocks`: a block whose span contains a clip's time becomes playable
 * (`hasClip: true`, its `id` swapped to the clip's option handle so the player can select + stream
 * it). Each clip is matched at most once. Playable clips that fall in no block are appended as
 * their own blocks, so a recent clip always shows even when the recorder kept no motion-sensor row
 * for it ("show all, play recent"). Pure; returned oldest-first.
 */
export function mergePlayable(
  blocks: CameraEvent[],
  playable: CameraEvent[],
  matchWindowMs: number = DEFAULT_MERGE_GAP_MS,
): CameraEvent[] {
  const used = new Set<string>();
  const marked = blocks.map((b) => {
    const end = b.endMs ?? b.startMs;
    const clip = playable.find(
      (c) =>
        !used.has(c.id) &&
        c.startMs >= b.startMs - matchWindowMs &&
        c.startMs <= end + matchWindowMs,
    );
    if (clip) {
      used.add(clip.id);
      return { ...b, id: clip.id, hasClip: true };
    }
    return b;
  });
  const leftovers = playable.filter((c) => !used.has(c.id));
  return [...marked, ...leftovers].sort((a, b) => a.startMs - b.startMs);
}
