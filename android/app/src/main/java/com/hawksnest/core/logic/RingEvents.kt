package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
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

/** The event selector's current options (`Motion 1`, `Ding 1`, …), newest-first, or empty. */
fun ringEventOptions(select: HassEntity?): List<String> =
    (select?.attributes?.get("options") as? JsonArray)
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
 * recent real time; when a time is missing (history gap) it falls back to the old even spacing so a
 * row never vanishes. Returned oldest-first to match the timeline's left→right order.
 */
fun ringEventsFromOptions(
    options: List<String>,
    timesDesc: List<Long>,
    cameraName: String,
    nowMs: Long,
): List<CameraEvent> =
    options.mapIndexed { i, opt ->
        CameraEvent(
            id = opt,
            camera = cameraName,
            label = labelOf(opt),
            startMs = timesDesc.getOrNull(i) ?: (nowMs - i * 6 * 60_000L),
            endMs = null,
            hasClip = true,
            hasSnapshot = false,
            thumbnailUrl = null,
            snapshotUrl = null,
        )
    }.sortedBy { it.startMs }

/**
 * Fallback used when we have no real event times yet: the selector's options on plain even spacing.
 * (This is the pre-Snowflake behavior — kept so the timeline still renders offline / before the
 * history query resolves.) Returned oldest-first.
 */
fun ringEventsFromSelect(
    select: HassEntity?,
    cameraName: String,
    nowMs: Long,
): List<CameraEvent> = ringEventsFromOptions(ringEventOptions(select), emptyList(), cameraName, nowMs)
