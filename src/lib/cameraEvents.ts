/**
 * Recorded-camera events + footage URLs — the data layer behind the Ring-style
 * timeline scrubber. Mirrors the `logbook.ts` shape/conventions (epoch ms,
 * defensive normalization) and the `cameraUrl.ts` "pure URL builder" style, and
 * has a 1:1 Kotlin port in `core/logic/CameraEvent.kt`.
 *
 * Live data comes from Frigate behind Home Assistant (the Frigate HA integration
 * exposes views under `/api/frigate/…`, reached same-origin through the nginx
 * proxy). Demo mode synthesizes events and points the footage URLs at the bundled
 * demo clip, so the whole scrubber is exercised with no backend.
 */

/** Frigate's recording/clip views, proxied same-origin through HA's nginx. */
export const FRIGATE_BASE = "/api/frigate";

/** Where the bundled demo clip + poster live (served from `public/demo/`). */
export const DEMO_CLIP_URL = "/demo/camera-loop.mp4";
export const DEMO_POSTER_URL = "/demo/camera-still.jpg";

/** One normalized recorded event (a motion/object detection with a clip). */
export interface CameraEvent {
  /** Frigate event id (stable; used to build clip/snapshot URLs). */
  id: string;
  /** Frigate camera name (its `camera.<name>` slug, not the HA entity_id). */
  camera: string;
  /** Detected object, e.g. "person" | "motion" | "car" | "dog". */
  label: string;
  /** Event start, epoch milliseconds. */
  startMs: number;
  /** Event end, epoch milliseconds, or null while the event is still ongoing. */
  endMs: number | null;
  hasClip: boolean;
  hasSnapshot: boolean;
  /** Thumbnail/snapshot URLs, or null when Frigate didn't capture one. */
  thumbnailUrl: string | null;
  snapshotUrl: string | null;
  /**
   * Frigate's GenAI description of what the object did, or null.
   *
   * Null is the common case and does **not** mean "loading":
   * - Frigate runs GenAI for **`person` only** (per-camera `objects.genai`), so a
   *   dog or cat event will never have one.
   * - It's generated **after the event ends** (the VLM runs on the host), so a
   *   fresh person event has none until a later refetch.
   *
   * The UI must tell those two apart — see `EventDescription`.
   */
  description: string | null;
}

/** The loose shape Frigate's `/api/events` returns (fields vary per entry). */
export interface RawFrigateEvent {
  id?: string;
  camera?: string;
  label?: string;
  /** Epoch *seconds* (float), like HA's logbook. */
  start_time?: number;
  end_time?: number | null;
  has_clip?: boolean;
  has_snapshot?: boolean;
  /** Frigate nests the GenAI description here. Arrives already, untouched: the HA
   *  integration proxies `/api/events` verbatim, so nothing new had to be fetched. */
  data?: { description?: string | null };
}

/**
 * Flatten a GenAI description into one line of plain prose.
 *
 * Frigate's *prompt* is where verbosity is actually fixed (the seed now asks for a
 * single sentence), but descriptions generated before that still sit in Frigate's
 * database and will keep arriving for as long as those events are retained. They
 * are multi-paragraph, and they arrive as raw markdown — `**bold**`, `##`
 * headings, `1.` lists — which nothing in this app renders, so the asterisks show
 * up literally on screen.
 *
 * So: strip the markup, collapse the structure to spaces. Cleaning here rather
 * than in the view means every consumer gets the same clean string, and the
 * two-line clamp actually clamps instead of being defeated by newlines.
 */
export function cleanDescription(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const text = raw
    // Fenced/inline code and emphasis markers: keep the words, drop the syntax.
    .replace(/[*_`]{1,3}/g, "")
    // Headings and blockquotes at the start of a line.
    .replace(/^\s{0,3}#{1,6}\s*/gm, "")
    .replace(/^\s{0,3}>\s?/gm, "")
    // Ordered and unordered list markers.
    .replace(/^\s{0,3}(?:\d+\.|[-+•])\s+/gm, "")
    // Any run of whitespace — including the paragraph breaks — becomes one space.
    .replace(/\s+/g, " ")
    .trim();
  return text || null;
}

/** Frigate sends times as epoch *seconds* (float). Normalize to ms. */
function secondsToMs(secs: number | undefined | null): number | null {
  if (typeof secs !== "number" || !Number.isFinite(secs)) return null;
  return Math.round(secs * 1000);
}

/**
 * The recorded-footage (HLS VOD) URL for `camera` over `[startMs, endMs]` — what
 * the scrubber loads when you seek to an arbitrary time. Frigate serves an HLS
 * playlist spanning any range. Pure builder; `base` is swappable for tests/demo.
 */
export function recordingUrlAt(
  camera: string,
  startMs: number,
  endMs: number,
  base: string = FRIGATE_BASE,
): string {
  // Rounded OUTWARDS, matching `clipExport.clipRangeSeconds` — Frigate's ranges are whole
  // seconds, and rounding a range inwards asks for slightly less footage than the caller
  // described. This floored both ends, so a page could come back a fraction short at its tail
  // and the last frame of a window was unreachable. Sub-second and invisible, which is precisely
  // why the two builders had drifted: nothing about the symptom points at the rounding.
  const start = Math.floor(startMs / 1000);
  const end = Math.ceil(endMs / 1000);
  return `${base}/vod/${encodeURIComponent(camera)}/start/${start}/end/${end}/master.m3u8`;
}

/**
 * In-media playback position (seconds) for a scrub to `headMs` within a VOD that spans
 * `[windowStartMs, …]`. Clamped to ≥ 0 so scrubbing *before* the window start can't address a
 * negative offset (which is the kind of out-of-range seek that crashes some players). Pairing this
 * with a single window-spanning VOD lets the player `seekTo()` instead of reloading a new playlist
 * per scrub — the fix for scrub stutter.
 */
export function vodPositionSeconds(headMs: number, windowStartMs: number): number {
  return Math.max(0, (headMs - windowStartMs) / 1000);
}

/** The downloadable clip (mp4) for a single recorded event. Pure builder. */
export function eventClipUrl(eventId: string, base: string = FRIGATE_BASE): string {
  return `${base}/notifications/${encodeURIComponent(eventId)}/clip.mp4`;
}

/**
 * The downloadable clip (mp4) for an **arbitrary** `[startSec, endSec]` range on `camera` — the
 * clip-export feature, as opposed to {@link eventClipUrl}, which can only address a whole event.
 *
 * Frigate generates it on demand (ffmpeg concat over the recording segments, stream copy) and
 * streams the result, so there is no `Content-Length` and no progress to report. The HA Frigate
 * integration exposes it as `RecordingProxyView` — note the path segment is `recording`, singular,
 * and that the `/clip.mp4` suffix is Frigate's, not HA's: the proxy appends it upstream.
 *
 * Seconds, not ms, because that is what Frigate's route takes. Use `clipExport.clipRangeSeconds`
 * to derive them — it rounds outwards to match ffmpeg's whole-second in/out points.
 */
export function clipExportUrl(
  camera: string,
  startSec: number,
  endSec: number,
  base: string = FRIGATE_BASE,
): string {
  return `${base}/recording/${encodeURIComponent(camera)}/start/${startSec}/end/${endSec}`;
}

/** The snapshot (jpg) for a single recorded event. Pure builder. */
export function eventSnapshotUrl(eventId: string, base: string = FRIGATE_BASE): string {
  return `${base}/notifications/${encodeURIComponent(eventId)}/snapshot.jpg`;
}

/**
 * Normalize Frigate's events payload into typed, **chronological (oldest-first)**
 * `CameraEvent[]` — the order a left-to-right timeline and prev/next stepping
 * want. Entries without an id or a usable start time are dropped. A missing
 * `end_time` means the event is still in progress (`endMs = null`).
 */
/**
 * Unwrap a `frigate/events/get` websocket result into the raw event list.
 *
 * The integration sends the result **without decoding it** — so it usually arrives as a JSON
 * *string* containing the array, not the array itself. Handle both (a future integration version
 * decoding server-side must not break us), and treat anything else as "no events": the caller's
 * contract is an empty timeline, never a throw.
 */
export function parseFrigateWsEvents(result: unknown): RawFrigateEvent[] {
  let raw: unknown = result;
  if (typeof raw === "string") {
    try {
      raw = JSON.parse(raw);
    } catch {
      return [];
    }
  }
  return Array.isArray(raw) ? (raw as RawFrigateEvent[]) : [];
}

export function normalizeFrigateEvents(
  raw: RawFrigateEvent[],
  base: string = FRIGATE_BASE,
): CameraEvent[] {
  return raw
    .map((e): CameraEvent | null => {
      const startMs = secondsToMs(e.start_time);
      if (!e.id || startMs === null) return null;
      const hasSnapshot = Boolean(e.has_snapshot);
      return {
        id: e.id,
        camera: e.camera ?? "",
        label: e.label ?? "motion",
        startMs,
        endMs: secondsToMs(e.end_time),
        hasClip: Boolean(e.has_clip),
        hasSnapshot,
        thumbnailUrl: hasSnapshot ? eventSnapshotUrl(e.id, base) : null,
        snapshotUrl: hasSnapshot ? eventSnapshotUrl(e.id, base) : null,
        // Normalize empty/whitespace to null so the UI has exactly one "absent"
        // case, and flatten legacy markdown essays to one line (see cleanDescription).
        description: cleanDescription(e.data?.description),
      };
    })
    .filter((e): e is CameraEvent => e !== null)
    .sort((a, b) => a.startMs - b.startMs);
}
