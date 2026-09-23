package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.stringAttr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ring-mqtt's per-event id is a **Ring Snowflake**: the upper bits are a millisecond timestamp.
 * Empirically (derived against recorder first-seen times across cameras) the real event time is
 * `(id ushr 22) - OFFSET`, accurate to well within a minute — the residual is just ring-mqtt's
 * processing latency. This is what lets the timeline plot true motion times instead of faking them.
 */
private const val RING_SNOWFLAKE_OFFSET_MS = 42_790_053_458L

/** Decode a ring-mqtt event id to its real epoch-ms event time, or null if it isn't a valid id. */
fun ringEventIdToMs(eventId: String?): Long? =
    eventId?.trim()?.toLongOrNull()?.let { (it ushr 22) - RING_SNOWFLAKE_OFFSET_MS }

/**
 * The recording ring-mqtt has published for the selector's **current** option.
 *
 * ring-mqtt 5.x does not create a `camera.<base>_event` entity — selecting an option makes it fetch
 * Ring's signed cloud recording and publish the URL as the selector's `recordingUrl` attribute (an
 * expiring S3 mp4, playable directly). Non-URL sentinels (`<Recording Not Found>`,
 * `<Transcoding in Progress>`) are not playable, so they read as "no URL". Mirrors the web helper.
 */
fun ringRecordingUrl(select: HassEntity?): String? =
    select?.stringAttr("recordingUrl")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

/**
 * True when ring-mqtt has said this selection has nothing to play — the event rotated out of Ring's
 * history, or there is no recording for it. Terminal (fail now), unlike `<Transcoding in Progress>`,
 * which resolves into a URL shortly.
 */
fun ringRecordingMissing(select: HassEntity?): Boolean =
    select?.stringAttr("recordingUrl")?.contains("Recording Not Found", ignoreCase = true) == true

/**
 * A full ISO-8601 instant embedded in an option string, or null.
 *
 * The zone designator is **required** so both platforms resolve the same string to the same
 * millisecond. Deliberately strict: the web twin used to run `Date.parse` over the option with its
 * leading word stripped, and `Date.parse("1")` — what `"Motion 1"` reduces to — is not `NaN` in
 * V8, it is 2001-01-01. Every ring option was being timed, wrongly, by two decades.
 */
private val ISO_INSTANT =
    Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})""")

/** The real time an option names, if it names one at all. `"Motion 1"` → null. */
fun ringOptionTimeMs(option: String): Long? =
    ISO_INSTANT.find(option)?.value?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    }

/**
 * The event selector's current options (`Motion 1`, `Ding 1`, …), newest-first, or empty.
 *
 * Empty when the selector is not reporting: HA restores a retired ring-mqtt entity with its last
 * `options` list intact, so a dead selector still *looks* like it has a handful of playable events
 * long after the camera it belonged to was deleted.
 */
fun ringEventOptions(select: HassEntity?): List<String> =
    (select?.takeIf { it.state !in DEAD_STATES }?.attributes?.get("options") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

private fun labelOf(option: String): String = when {
    option.contains("ding", ignoreCase = true) -> "ding"
    option.contains("motion", ignoreCase = true) -> "motion"
    else -> "event"
}

/**
 * Build timeline `CameraEvent`s from the selector's current [options] (which stay the playable
 * `Motion N` handles) paired with REAL event times in [timesDesc] (newest-first, decoded from the
 * event ids via [ringEventIdToMs]). Option *i* — the *i*-th most recent — takes the *i*-th most
 * recent real time. Returned oldest-first to match the timeline's left→right order.
 *
 * **An option with no recoverable time is dropped, not placed.** It used to fall back to
 * `nowMs - i * 6 * 60_000`, which drew invented moments in exactly the same ink as real ones:
 * `camera.front` inherited 30 frozen options from a retired Ring camera and rendered them as a
 * comb of evenly spaced "recordings" that had never happened, each claiming `hasClip = true`.
 * There is no `nowMs` parameter any more — with no "now" in scope, no time can be invented.
 */
fun ringEventsFromOptions(
    options: List<String>,
    timesDesc: List<Long>,
    cameraName: String,
): List<CameraEvent> =
    options.mapIndexedNotNull { i, opt ->
        val startMs = timesDesc.getOrNull(i) ?: ringOptionTimeMs(opt) ?: return@mapIndexedNotNull null
        CameraEvent(
            id = opt,
            camera = cameraName,
            label = labelOf(opt),
            startMs = startMs,
            endMs = null,
            hasClip = true,
            hasSnapshot = false,
            thumbnailUrl = null,
            snapshotUrl = null,
        )
    }.sortedBy { it.startMs }

/**
 * The selector's options, timed only by whatever each option string carries itself. Used where no
 * decoded history is available (the web has no `fetchAttributeHistory` seam). Returned oldest-first;
 * options like `Motion 1` carry no time and so contribute nothing.
 */
fun ringEventsFromSelect(
    select: HassEntity?,
    cameraName: String,
): List<CameraEvent> = ringEventsFromOptions(ringEventOptions(select), emptyList(), cameraName)
