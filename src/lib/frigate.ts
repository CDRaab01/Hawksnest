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
/** Per-camera `record.continuous.days`, from the same config read. */
let retentionCache: Map<string, number> = new Map();
let camerasFetchedAt = 0;
let camerasInFlight: Promise<Set<string>> | null = null;
const CAMERAS_TTL_MS = 60_000;

/** Shape of the slice of `/api/frigate/config` this module reads. */
interface FrigateConfig {
  cameras?: Record<string, { record?: { continuous?: { days?: number } } }>;
  record?: { continuous?: { days?: number } };
}

/**
 * Fetch (and cache) Frigate's camera list. Safe to call repeatedly; resolves once
 * the cache is populated so a caller can re-render on the result.
 *
 * `/api/frigate/config` was **confirmed against the real cluster 2026-07-29** (it had previously
 * been inferred): it returns 200 through the HA proxy and carries both the camera list and each
 * camera's `record.continuous.days`. The fail-closed behaviour below is kept anyway — a failed
 * read still reports "no Frigate", which degrades to today's behaviour rather than a broken
 * recorded path.
 */
export function primeFrigateCameras(): Promise<void> {
  const fresh = Date.now() - camerasFetchedAt < CAMERAS_TTL_MS;
  if (camerasCache && fresh) return Promise.resolve();
  if (!camerasInFlight) {
    camerasInFlight = fetch("/api/frigate/config", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : {}))
      .then((json: FrigateConfig) => {
        const cams = json?.cameras ?? {};
        // Retention is read from the SAME response — it is per-camera in Frigate, and a
        // deployment can legitimately keep different windows per room (a bedroom shorter than
        // a hallway), so this must not collapse to one global number.
        const globalDays = json?.record?.continuous?.days;
        const retention = new Map<string, number>();
        for (const [name, cam] of Object.entries(cams)) {
          const days = cam?.record?.continuous?.days ?? globalDays;
          if (typeof days === "number" && Number.isFinite(days) && days > 0) {
            retention.set(name, days);
          }
        }
        retentionCache = retention;
        return new Set(Object.keys(cams));
      })
      .catch(() => {
        retentionCache = new Map();
        return new Set<string>();
      })
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
 * How many days of continuous recording Frigate keeps for `name`, or null if unknown.
 *
 * Null means "config not read yet, or this camera is not Frigate's" — callers should fall back
 * rather than assume a span, because guessing high shows a timeline reaching into recordings
 * that were pruned, which reads as a broken player rather than an empty one.
 */
export function frigateRetentionDays(name: string): number | null {
  return retentionCache.get(name) ?? null;
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

/** Drop the cached camera list and retention. Tests only — the app fetches once per session. */
export function resetFrigateCamerasCache(): void {
  camerasCache = null;
  retentionCache = new Map();
  camerasFetchedAt = 0;
  camerasInFlight = null;
}
