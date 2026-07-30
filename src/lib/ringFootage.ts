import type { CameraEvent } from "./cameraEvents";
import { clipContaining, offsetInClipSeconds } from "./clipSeek";

/**
 * The `ring-timeline` service's **24/7 continuous track** (`/footage`) — the thing `/timeline`
 * structurally cannot show.
 *
 * `/timeline` is Ring's `video_search`, which only ever returns discrete *events*. That is why a
 * quiet 3–5 AM window comes back empty even on the seven cameras that record continuously: nothing
 * triggered, so there is no event, even though the footage exists. `/footage` reads Ring's Event
 * Video Manager timeline instead and returns the stitched continuous spans.
 *
 * Two consequences shape everything here:
 *  - Ring stitches server-side: a request for a wide window comes back as ONE segment covering all
 *    of it, not a pile of chunks. So the normal case is a single URL the player seeks around in —
 *    the same "one VOD for the whole window" shape the Frigate path already uses, which is why
 *    scrubbing across it doesn't re-init the player.
 *  - Not every camera has it. The battery cameras and the doorbell record events only, and answer
 *    with an empty list — an honest "no continuous track", not a failure. `continuous` says which.
 *
 * Pure + unit-tested; has a 1:1 Kotlin port in `core/logic/RingFootage.kt`. The I/O lives in
 * `ringTimeline.ts` (web) / `RingTimelineClient.kt` (Android) so this stays testable in both.
 */

/** One stitched span of continuous recording (times are epoch ms). */
export interface FootageSegment {
  startMs: number;
  endMs: number;
  url: string | null;
  /** When the pre-signed URL dies (~15 min out) — after this the footage must be refetched. */
  urlExpiresAtMs: number | null;
  /** Ring can mark a span end-to-end encrypted; playing it needs a key no server here holds. */
  encrypted: boolean;
  chunked: boolean;
  dingId: string | null;
}

export interface RingFootage {
  segments: FootageSegment[];
  /** False for the battery cameras and the doorbell — they record events only. */
  continuous: boolean;
  /** Earliest signed-URL expiry in the set; the player refetches before this. */
  expiresAtMs: number | null;
  /** Ring capped the result: the window is not fully covered by these segments. */
  truncated: boolean;
}

/** A drawable run of the continuous lane — neighbouring segments coalesced (see {@link footageSpans}). */
export interface FootageSpan {
  startMs: number;
  endMs: number;
  /** False for encrypted/URL-less spans: footage exists but this player cannot show it. */
  playable: boolean;
}

const EMPTY: RingFootage = {
  segments: [],
  continuous: false,
  expiresAtMs: null,
  truncated: false,
};

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/** A segment is playable when it has a URL and isn't end-to-end encrypted. */
export function isPlayable(seg: FootageSegment): boolean {
  return seg.url !== null && !seg.encrypted;
}

/**
 * Parse `/footage`. Segments with unusable times are dropped rather than failing the whole
 * response — one malformed span must not cost the camera its continuous track.
 *
 * Encrypted and URL-less segments are KEPT. They are real coverage, and the lane showing them
 * greyed is the honest answer; hiding them would draw a gap where footage actually exists.
 */
export function parseRingFootage(body: unknown): RingFootage {
  if (typeof body !== "object" || body === null) return EMPTY;
  const raw = (body as { segments?: unknown }).segments;
  const list = Array.isArray(raw) ? raw : [];

  const segments: FootageSegment[] = [];
  for (const item of list) {
    if (typeof item !== "object" || item === null) continue;
    const s = item as Record<string, unknown>;
    const startMs = num(s.startMs);
    const endMs = num(s.endMs);
    // A zero/negative span can't be seeked into and would draw as an invisible sliver.
    if (startMs === null || endMs === null || endMs <= startMs) continue;
    segments.push({
      startMs,
      endMs,
      url: typeof s.url === "string" ? s.url : null,
      urlExpiresAtMs: num(s.urlExpiresAtMs),
      encrypted: s.encrypted === true,
      chunked: s.chunked === true,
      dingId: typeof s.dingId === "string" ? s.dingId : null,
    });
  }
  segments.sort((a, b) => a.startMs - b.startMs);

  const expiries = segments.flatMap((s) => (s.urlExpiresAtMs !== null ? [s.urlExpiresAtMs] : []));
  return {
    segments,
    // Trust our own parse over the server's flag: if nothing survived, there is no track to show.
    continuous: segments.length > 0,
    // Refresh against the FIRST URL to die, not the last.
    expiresAtMs: expiries.length ? Math.min(...expiries) : null,
    truncated: (body as { truncated?: unknown }).truncated === true,
  };
}

/**
 * The segment covering `t`, or null. The interval is half-open (`start <= t < end`) so two
 * back-to-back segments never both claim the boundary instant — a closed interval made the player's
 * segment-keyed effects thrash between two ids while scrubbing across a seam.
 * On genuine overlap the latest-starting segment wins, matching `clipContaining`.
 */
export function footageSegmentAt(segments: FootageSegment[], t: number): FootageSegment | null {
  let best: FootageSegment | null = null;
  for (const seg of segments) {
    if (t < seg.startMs || t >= seg.endMs) continue;
    if (!best || seg.startMs >= best.startMs) best = seg;
  }
  return best;
}

/** Offset of `t` within `seg`, clamped into the span, in seconds (the player seeks in seconds). */
export function offsetInSegmentSeconds(seg: FootageSegment, t: number): number {
  const span = seg.endMs - seg.startMs;
  return Math.min(Math.max(0, t - seg.startMs), Math.max(0, span)) / 1000;
}

/**
 * Segments coalesced into drawable runs. Ring normally answers with one stitched segment, but a
 * window that spans a recording restart comes back as several abutting ones; drawn individually
 * they show hairline seams that read as gaps in coverage — which is exactly the thing this lane
 * exists to disprove. Only same-playability neighbours merge, so a greyed encrypted run stays
 * visually distinct from the playable footage either side of it.
 */
export function footageSpans(segments: FootageSegment[], toleranceMs = 1000): FootageSpan[] {
  const spans: FootageSpan[] = [];
  for (const seg of [...segments].sort((a, b) => a.startMs - b.startMs)) {
    const playable = isPlayable(seg);
    const last = spans[spans.length - 1];
    if (last && last.playable === playable && seg.startMs - last.endMs <= toleranceMs) {
      last.endMs = Math.max(last.endMs, seg.endMs);
      continue;
    }
    spans.push({ startMs: seg.startMs, endMs: seg.endMs, playable });
  }
  return spans;
}

/**
 * What the player should show for a scrubbed moment. Pure so the two platforms cannot drift on the
 * one decision users actually notice — which of two possible sources plays.
 *
 * **Continuous footage wins over an event clip when both cover the moment.** Two reasons, both
 * borne out by the existing code: the whole window is one media source, so scrubbing across it
 * seeks instead of tearing down and re-initialising the player per clip (the documented cause of
 * the old scrub stutter and the backwards-seek crash); and the event blocks stay drawn on top as
 * markers, so nothing is lost by not *playing* them — tapping one still seeks to it, now inside a
 * continuous stream. Event clips remain the source on the cameras with no 24/7 track at all.
 */
/**
 * Tolerance when coalescing Frigate recording segments into drawable spans.
 *
 * Frigate writes ~10 s cache segments with sub-second seams between them, but a camera reconnect
 * or a Frigate restart can drop a segment, leaving a one-segment hole that is real but not worth
 * drawing. 15 s bridges those; anything longer renders as an honest gap in the lane.
 */
const FRIGATE_SPAN_TOLERANCE_MS = 15_000;

/**
 * Unwrap a `frigate/recordings/get` websocket result into drawable [FootageSpan]s — the Frigate
 * counterpart of `footageSpans`, and the data behind the continuous lane for Frigate cameras.
 *
 * Same websocket-only contract as `frigate/events/get` (see `parseFrigateWsEvents`): there is no
 * REST route for this, and the result usually arrives as a JSON **string** the integration didn't
 * decode. The payload is one entry per ~10 s recording segment (measured: ~6.5k entries / 1 MB /
 * tens of ms for a 3-day window), so coalescing here — not in the component — is what keeps the
 * timeline from mapping thousands of DOM nodes.
 *
 * Spans are always `playable: true`: unlike Ring, Frigate has no per-segment URL to expire and no
 * end-to-end encryption — if the segment is on disk, the VOD can serve it. Junk input yields [],
 * never a throw: the lane simply doesn't render, which is what the pre-8b timeline showed anyway.
 */
export function parseFrigateWsRecordings(
  result: unknown,
  toleranceMs: number = FRIGATE_SPAN_TOLERANCE_MS,
): FootageSpan[] {
  let raw: unknown = result;
  if (typeof raw === "string") {
    try {
      raw = JSON.parse(raw);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(raw)) return [];
  const segments = raw
    .map((r) => {
      const rec = (r ?? {}) as Record<string, unknown>;
      const start = num(rec.start_time);
      const end = num(rec.end_time);
      if (start === null || end === null || end <= start) return null;
      return { startMs: Math.round(start * 1000), endMs: Math.round(end * 1000) };
    })
    .filter((s): s is { startMs: number; endMs: number } => s !== null)
    .sort((a, b) => a.startMs - b.startMs);
  const spans: FootageSpan[] = [];
  for (const seg of segments) {
    const last = spans[spans.length - 1];
    if (last && seg.startMs - last.endMs <= toleranceMs) {
      last.endMs = Math.max(last.endMs, seg.endMs);
      continue;
    }
    spans.push({ startMs: seg.startMs, endMs: seg.endMs, playable: true });
  }
  return spans;
}

export type RecordedSource =
  | { kind: "footage"; url: string; seekSeconds: number; segment: FootageSegment }
  | { kind: "clip"; url: string; seekSeconds: number; event: CameraEvent }
  /** Footage exists here but this player can't decode it — say so, don't show an empty frame. */
  | { kind: "encrypted" }
  | { kind: "none" };

export function chooseRecordedSource(args: {
  headMs: number;
  segments: FootageSegment[];
  events: CameraEvent[];
  /** Event id → playable URL (the timeline's `urls` map). */
  urls: Map<string, string>;
  loadedClipId: string | null;
  loadedDurationMs: number | null;
}): RecordedSource {
  const { headMs, segments, events, urls, loadedClipId, loadedDurationMs } = args;

  const seg = footageSegmentAt(segments, headMs);
  if (seg && isPlayable(seg)) {
    return {
      kind: "footage",
      url: seg.url!,
      seekSeconds: offsetInSegmentSeconds(seg, headMs),
      segment: seg,
    };
  }

  const event = clipContaining(events, headMs, loadedClipId, loadedDurationMs);
  const url = event ? (urls.get(event.id) ?? null) : null;
  if (event && url) {
    return { kind: "clip", url, seekSeconds: offsetInClipSeconds(event, headMs), event };
  }

  // Only now does an unplayable segment matter: with no clip to fall back on, "encrypted" is the
  // true reason there's no picture, and it is not the same message as "nothing was recorded".
  if (seg) return { kind: "encrypted" };
  return { kind: "none" };
}
