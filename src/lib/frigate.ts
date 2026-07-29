/**
 * Client helpers for the **Frigate** recorded/NVR path, the counterpart to
 * `go2rtc.ts`'s live path. Frigate is reached through Home Assistant's
 * frigate-hass-integration (`/api/frigate/…`, same-origin via the app's nginx),
 * not proxied directly — that's what keeps HA-token auth and the Frigate
 * entities. The Frigate camera name is kept equal to the HA camera base
 * (`camera.<base>` → `<base>`), same convention as go2rtc stream names.
 *
 * This module answers exactly one question: **does Frigate know this camera?**
 * That's what separates "a real NVR is recording this, treat playback as a
 * seekable VOD" from "there's no NVR, this is the demo loop".
 */

/**
 * Frigate camera names, fetched once per session. `null` = not fetched yet.
 *
 * **Fails closed**, and that's the opposite of `go2rtc.ts` on purpose. go2rtc is
 * optimistic because guessing wrong costs one fast WebSocket failure and a
 * step-down to the next live tier. Guessing wrong here is worse and silent: a
 * camera wrongly believed to be on Frigate stops looping the demo clip and starts
 * surfacing playback errors for recordings that were never going to exist. So an
 * unfetched or failed config read reports "no Frigate", which is exactly the
 * behaviour the app had before Frigate existed.
 */
let camerasCache: Set<string> | null = null;
let camerasFetchedAt = 0;
let camerasInFlight: Promise<Set<string>> | null = null;
const CAMERAS_TTL_MS = 60_000;

/**
 * Fetch (and cache) Frigate's camera list. Safe to call repeatedly; resolves once
 * the cache is populated so a caller can re-render on the result.
 *
 * NOTE: `/api/frigate/config` is the one route in this integration that is
 * **inferred rather than confirmed** — `/vod/…`, `/notifications/<id>/clip.mp4`
 * and `/events` are all exercised by shipped code, this one is not. nginx will
 * forward it (the `/api/frigate/` location is a prefix proxy), but whether
 * frigate-hass-integration serves it has to be checked against a real cluster.
 * If it turns out not to, the fallback is reading the camera list off the HA
 * `camera.*` entity attributes the integration creates; the fail-closed cache
 * above means that discovery lands as "no Frigate" and today's behaviour holds
 * until then, rather than as a broken recorded path.
 */
export function primeFrigateCameras(): Promise<void> {
  const fresh = Date.now() - camerasFetchedAt < CAMERAS_TTL_MS;
  if (camerasCache && fresh) return Promise.resolve();
  if (!camerasInFlight) {
    camerasInFlight = fetch("/api/frigate/config", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : {}))
      .then((json: { cameras?: Record<string, unknown> }) =>
        new Set(Object.keys(json?.cameras ?? {})),
      )
      .catch(() => new Set<string>())
      .then((set) => {
        camerasCache = set;
        camerasFetchedAt = Date.now();
        camerasInFlight = null;
        return set;
      });
  }
  return camerasInFlight.then(() => undefined);
}

/**
 * Whether Frigate is recording `name`. False until the config fetch has landed
 * AND named this camera — see the fail-closed note above. Pure and synchronous so
 * it can be called during render; pair it with `primeFrigateCameras()` in an
 * effect and re-render on the resolve.
 */
export function frigateHasCamera(name: string): boolean {
  return camerasCache?.has(name) ?? false;
}

/** Drop the cached camera list. Tests only — the app fetches once per session. */
export function resetFrigateCamerasCache(): void {
  camerasCache = null;
  camerasFetchedAt = 0;
  camerasInFlight = null;
}
