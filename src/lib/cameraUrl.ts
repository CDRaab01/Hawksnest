import type { HassEntity } from "./ha";

const PROXY = "/api/camera_proxy/";
const STREAM = "/api/camera_proxy_stream/";

/**
 * Resolve a root-relative HA path (`/api/...`) against the connected Home
 * Assistant base URL.
 *
 * HA pushes `entity_picture` as a root-relative path; an `<img>` resolves that
 * against the *page* origin. That's only correct when Hawksnest is served
 * through HA's reverse proxy (same origin). When the user points Settings
 * straight at HA (e.g. `http://homeassistant.local:8123`), the WebSocket
 * connects there but the camera `<img>` would still hit the page origin and
 * 404 — so we prefix the configured base. Same-origin deployments pass
 * `baseUrl === window.location.origin`, making this a no-op; an already-absolute
 * `entity_picture` or an empty `baseUrl` is left untouched.
 */
function withBase(path: string, baseUrl?: string): string {
  if (!baseUrl || !path.startsWith("/")) return path;
  return `${baseUrl.replace(/\/+$/, "")}${path}`;
}

/**
 * The signed snapshot URL Home Assistant pushes on a camera entity. HA sets
 * `entity_picture` to `/api/camera_proxy/<entity>?token=<signed>` and rotates
 * the token, so we always read it live off the attribute and never cache it.
 * Resolved against `baseUrl` (the connected HA origin) when given. Returns null
 * when absent (demo mode, or a non-camera entity).
 */
export function snapshotUrl(entity: HassEntity, baseUrl?: string): string | null {
  const pic = entity.attributes.entity_picture;
  if (typeof pic !== "string" || pic.length === 0) return null;
  return withBase(pic, baseUrl);
}

/**
 * Snapshot URL with a cache-buster bucket appended, so an `<img>` refetches a
 * fresh frame. `bucket` is a coarse time bucket (e.g. epoch/refreshMs) shared
 * across tiles so every camera refreshes on the same beat.
 */
export function snapshotUrlAt(
  entity: HassEntity,
  bucket: number,
  baseUrl?: string,
): string | null {
  const base = snapshotUrl(entity, baseUrl);
  if (!base) return null;
  const sep = base.includes("?") ? "&" : "?";
  return `${base}${sep}_=${bucket}`;
}

/**
 * The MJPEG live-stream URL, derived from the snapshot URL by swapping the
 * proxy path (reusing the same signed token). HA serves this as
 * `multipart/x-mixed-replace`, renderable dependency-free in an `<img>`.
 * Resolved against `baseUrl` like the snapshot. Returns null if there's no
 * signed snapshot URL to derive it from.
 */
export function streamUrl(entity: HassEntity, baseUrl?: string): string | null {
  const pic = entity.attributes.entity_picture;
  if (typeof pic !== "string" || !pic.includes(PROXY)) return null;
  return withBase(pic.replace(PROXY, STREAM), baseUrl);
}

const UNAVAILABLE = new Set(["unavailable", "unknown"]);

/** A camera we can actually render a frame for (has a signed URL, not down). */
export function isCameraLive(entity: HassEntity): boolean {
  return !UNAVAILABLE.has(entity.state) && snapshotUrl(entity) !== null;
}
