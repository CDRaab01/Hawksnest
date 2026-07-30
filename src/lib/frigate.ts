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
 *
 * ## Why this reads entities instead of Frigate's config
 *
 * It used to fetch `/api/frigate/config`. **That route does not exist.**
 * frigate-hass-integration proxies snapshot, recording, thumbnail, clips, notifications, vod,
 * jsmpeg, mse, webrtc and go2rtc — not config — so the request 404s and every camera resolved as
 * "no Frigate", silently disabling recorded playback. Verified against the running cluster
 * 2026-07-30. (An earlier note in this file recorded the route as confirmed; what had actually
 * been tested was `frigate:5000/api/config` **direct**, which the browser cannot reach.)
 *
 * The fallback this module's original author predicted is the one now used: the integration
 * stamps `client_id` and `camera_name` onto the `camera.*` entity it creates, so membership is
 * readable from state we already hold. That is strictly better than the fetch — synchronous, no
 * network, no cache, and it cannot go stale.
 */

import type { HassEntity } from "./ha";

/**
 * Fallback days of continuous recording for the Frigate timeline, used only when
 * [FRIGATE_RETENTION_SENSOR] is absent/unavailable. The real value is served by an
 * HA REST sensor (`hawksnest-automation` HA seed) that reads Frigate's own
 * `record.continuous.days` — HA can reach Frigate's in-cluster config API, the
 * browser can't (the integration never proxies it, see above).
 *
 * Failure modes are asymmetric, which is why a stale fallback is tolerable: too LOW
 * and some kept footage is simply unreachable; too HIGH and the timeline offers days
 * that 404, which degrades to the "no recording kept" placeholder rather than breaking.
 */
export const FRIGATE_RETENTION_DAYS = 3;

/** The HA REST sensor surfacing Frigate's `record.continuous.days` (see above). */
export const FRIGATE_RETENTION_SENSOR = "sensor.frigate_retention_days";

/**
 * Days of retention to size the Frigate timeline with: the retention sensor's value
 * when it reads as a positive number, else the [FRIGATE_RETENTION_DAYS] fallback
 * (sensor missing, `unavailable`, `unknown` — e.g. an HA that predates the sensor).
 */
export function frigateRetentionDays(entity: HassEntity | null | undefined): number {
  const days = Number(entity?.state);
  return Number.isFinite(days) && days > 0 ? days : FRIGATE_RETENTION_DAYS;
}

/**
 * Whether this camera is recorded by Frigate, judged from its HA entity.
 *
 * Still **fails closed**, and that's the opposite of `go2rtc.ts` on purpose. go2rtc is optimistic
 * because guessing wrong costs one fast WebSocket failure and a step-down to the next live tier.
 * Guessing wrong here is worse and silent: a camera wrongly believed to be on Frigate stops
 * looping the demo clip and starts surfacing playback errors for recordings that were never going
 * to exist. A missing or unrecognised entity therefore reports "no Frigate", which is exactly the
 * behaviour the app had before Frigate existed.
 */
export function isFrigateCamera(entity: HassEntity | null | undefined): boolean {
  const attrs = entity?.attributes as Record<string, unknown> | undefined;
  if (!attrs) return false;
  // The integration sets both; requiring both avoids matching some other integration that
  // happens to use one of these names.
  return typeof attrs.client_id === "string" && typeof attrs.camera_name === "string";
}

/**
 * The Frigate camera name for an entity, which is authoritative over the entity id.
 *
 * Normally identical to the object id, but the integration reports what Frigate itself calls the
 * camera — and every VOD/event URL must use that, not the HA slug, or the request 404s.
 */
export function frigateCameraName(entity: HassEntity | null | undefined): string | null {
  const name = (entity?.attributes as Record<string, unknown> | undefined)?.camera_name;
  return typeof name === "string" && name.length > 0 ? name : null;
}
