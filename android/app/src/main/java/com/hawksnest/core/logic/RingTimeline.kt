package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The `ring-timeline` service's model — Ring's own recorded-footage timeline. Ported 1:1 from
 * `src/lib/ringTimeline.ts` (with its test suite).
 *
 * It exists because Home Assistant can't provide this: ring-mqtt publishes a 40-slot event `select`
 * with **no event times** (which is why timeline blocks used to sit at fabricated 6-minute spacing)
 * and yields nothing playable at all on the wired cameras. Ring's own API has real times, real
 * spans, thumbnails and pre-signed mp4 URLs — this is that, normalized.
 */

/** One Ring camera as the service sees it (`slug` is ring-mqtt's slugging of the device name). */
data class RingDevice(val id: Long, val name: String, val slug: String)

/** A recording, as returned by the service (times are epoch ms). */
data class RingRecording(
    val id: String,
    val startMs: Long,
    val endMs: Long?,
    val durationSec: Long?,
    val kind: String,
    val person: Boolean,
    val url: String?,
    /** When the pre-signed URL dies (~15 min out) — after this the timeline must be refetched. */
    val urlExpiresAtMs: Long?,
    val thumbnailUrl: String?,
)

/** A camera's recordings plus their playable URLs, and when the earliest URL expires. */
data class RingTimeline(
    val events: List<CameraEvent>,
    val urls: Map<String, String>,
    val expiresAtMs: Long?,
    /** Ring capped the result: there are older recordings in the window than these. */
    val truncated: Boolean,
)

/**
 * Normalize a Ring device name and an HA camera name the same way, so the two can be matched.
 * HA entity ids are NOT usable for this — they froze at first discovery and have drifted from the
 * Ring names since (HA's `camera.front_*` is Ring's "Front Driveway", `back2` is "Back Side Yard").
 */
fun nameSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** The Ring device backing an HA camera, by name; falls back to the entity base for old setups. */
fun matchDevice(devices: List<RingDevice>, cameraName: String, entityBase: String): RingDevice? {
    val byName = nameSlug(cameraName)
    return devices.firstOrNull { it.slug == byName }
        ?: devices.firstOrNull { it.slug == nameSlug(entityBase) }
}

/** Ring's `kind` → the timeline's label vocabulary (never throws on a kind we haven't seen). */
private fun labelOf(kind: String, person: Boolean): String = when {
    kind == "ding" || kind == "doorbell" -> "ding"
    person -> "person"
    kind == "on_demand" || kind == "on_demand_link" -> "event"
    else -> "motion"
}

/** Shape a service recording into the timeline's [CameraEvent]. */
fun toCameraEvent(rec: RingRecording, cameraName: String): CameraEvent = CameraEvent(
    id = rec.id,
    camera = cameraName,
    label = labelOf(rec.kind, rec.person),
    startMs = rec.startMs,
    // Unlike ring-mqtt's events these carry a real duration, so the block is the real span and no
    // longer has to be learned from the loaded media.
    endMs = rec.endMs,
    hasClip = rec.url != null,
    hasSnapshot = rec.thumbnailUrl != null,
    thumbnailUrl = rec.thumbnailUrl,
    snapshotUrl = rec.thumbnailUrl,
)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/** Parse `/cameras`, dropping malformed entries rather than failing on one bad device. */
fun parseRingDevices(body: JsonArray): List<RingDevice> = body.mapNotNull { element ->
    val obj = element as? JsonObject ?: return@mapNotNull null
    val id = obj.long("id") ?: return@mapNotNull null
    val name = obj.str("name") ?: return@mapNotNull null
    val slug = obj.str("slug") ?: return@mapNotNull null
    RingDevice(id, name, slug)
}

/**
 * Parse `/timeline` into the player's shape. Recordings without a URL are dropped: the Ring-style
 * invariant is that every block on the scrubber is watchable.
 */
fun parseRingTimeline(body: JsonObject, cameraName: String): RingTimeline {
    val raw = (body["events"] as? JsonArray).orEmpty()
    val recordings = raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id") ?: return@mapNotNull null
        val startMs = obj.long("startMs") ?: return@mapNotNull null
        RingRecording(
            id = id,
            startMs = startMs,
            endMs = obj.long("endMs"),
            durationSec = obj.long("durationSec"),
            kind = obj.str("kind") ?: "motion",
            person = obj.bool("person"),
            url = obj.str("url"),
            urlExpiresAtMs = obj.long("urlExpiresAtMs"),
            thumbnailUrl = obj.str("thumbnailUrl"),
        )
    }
    val playable = recordings.filter { it.url != null }
    return RingTimeline(
        events = playable.map { toCameraEvent(it, cameraName) },
        urls = playable.associate { it.id to it.url!! },
        // The player refreshes against the FIRST URL to die, not the last.
        expiresAtMs = recordings.mapNotNull { it.urlExpiresAtMs }.minOrNull(),
        truncated = body.bool("truncated"),
    )
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
