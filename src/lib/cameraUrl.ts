import type { HassEntity } from "./ha";

const PROXY = "/api/camera_proxy/";
const STREAM = "/api/camera_proxy_stream/";

/**
 * The signed snapshot URL Home Assistant pushes on a camera entity. HA sets
 * `entity_picture` to `/api/camera_proxy/<entity>?token=<signed>` and rotates
 * the token, so we always read it live off the attribute and never cache it.
 * Returns null when absent (demo mode, or a non-camera entity).
 */
export function snapshotUrl(entity: HassEntity): string | null {
  const pic = entity.attributes.entity_picture;
  return typeof pic === "string" && pic.length > 0 ? pic : null;
}

/**
 * Snapshot URL with a cache-buster bucket appended, so an `<img>` refetches a
 * fresh frame. `bucket` is a coarse time bucket (e.g. epoch/refreshMs) shared
 * across tiles so every camera refreshes on the same beat.
 */
export function snapshotUrlAt(entity: HassEntity, bucket: number): string | null {
  const base = snapshotUrl(entity);
  if (!base) return null;
  const sep = base.includes("?") ? "&" : "?";
  return `${base}${sep}_=${bucket}`;
}

/**
 * The MJPEG live-stream URL, derived from the snapshot URL by swapping the
 * proxy path (reusing the same signed token). HA serves this as
 * `multipart/x-mixed-replace`, renderable dependency-free in an `<img>`.
 * Returns null if there's no signed snapshot URL to derive it from.
 */
export function streamUrl(entity: HassEntity): string | null {
  const base = snapshotUrl(entity);
  if (!base || !base.includes(PROXY)) return null;
  return base.replace(PROXY, STREAM);
}

const UNAVAILABLE = new Set(["unavailable", "unknown"]);

/** A camera we can actually render a frame for (has a signed URL, not down). */
export function isCameraLive(entity: HassEntity): boolean {
  return !UNAVAILABLE.has(entity.state) && snapshotUrl(entity) !== null;
}
