package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client helpers for the **Frigate** recorded/NVR path — the port of
 * `src/lib/frigate.ts`. This module answers exactly one question: **does Frigate
 * know this camera?** That's what separates "a real NVR is recording this, treat
 * playback as a seekable VOD" from "there's no NVR, this is the demo loop".
 *
 * Membership is read straight off the camera's HA entity: the
 * frigate-hass-integration stamps `client_id` and `camera_name` onto the
 * `camera.*` entity it creates. No fetch, no cache, no priming — the
 * `/api/frigate/config` route this could have polled does not exist (verified
 * against the running cluster 2026-07-30; the integration proxies media routes,
 * not config).
 */

/**
 * Whether this camera is recorded by Frigate, judged from its HA entity.
 *
 * **Fails closed**, and that's the opposite of the go2rtc gate on purpose. go2rtc
 * is optimistic because guessing wrong costs one fast WebSocket failure and a
 * step-down to the next live tier. Guessing wrong here is worse and silent: a
 * camera wrongly believed to be on Frigate stops looping the demo clip and starts
 * surfacing playback errors for recordings that were never going to exist. A
 * missing or unrecognised entity therefore reports "no Frigate".
 *
 * The integration sets both attributes; requiring both avoids matching some other
 * integration that happens to use one of the names. The check is strict about JSON
 * *strings* — `core.ha.stringAttr` would stringify a number, which the web twin's
 * `typeof === "string"` rejects, so this uses its own gate.
 */
fun isFrigateCamera(entity: HassEntity?): Boolean =
    entity?.jsonStringAttr("client_id") != null && entity.jsonStringAttr("camera_name") != null

/**
 * The Frigate camera name for an entity, which is authoritative over the entity
 * id: the integration reports what Frigate itself calls the camera, and every
 * VOD/event URL must use that, not the HA slug, or the request 404s.
 */
fun frigateCameraName(entity: HassEntity?): String? =
    entity?.jsonStringAttr("camera_name")?.takeIf { it.isNotEmpty() }

/** A JSON **string** attribute, or null — a number/bool attribute is not a match. */
private fun HassEntity.jsonStringAttr(key: String): String? =
    (attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The HA REST sensor surfacing Frigate's `record.continuous.days` (web twin in `frigate.ts`). */
const val FRIGATE_RETENTION_SENSOR = "sensor.frigate_retention_days"

/**
 * Days of retention to size the Frigate timeline with: the retention sensor's value
 * when it reads as a positive number, else [fallback] (sensor missing, `unavailable`,
 * `unknown` — e.g. an HA that predates the sensor).
 */
fun frigateRetentionDays(entity: HassEntity?, fallback: Double): Double =
    entity?.state?.toDoubleOrNull()?.takeIf { it > 0 } ?: fallback
