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
  const start = Math.floor(startMs / 1000);
  const end = Math.floor(endMs / 1000);
  return `${base}/vod/${encodeURIComponent(camera)}/start/${start}/end/${end}/master.m3u8`;
}

/** The downloadable clip (mp4) for a single recorded event. Pure builder. */
export function eventClipUrl(eventId: string, base: string = FRIGATE_BASE): string {
  return `${base}/notifications/${encodeURIComponent(eventId)}/clip.mp4`;
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
      };
    })
    .filter((e): e is CameraEvent => e !== null)
    .sort((a, b) => a.startMs - b.startMs);
}
