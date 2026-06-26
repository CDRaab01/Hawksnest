package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure parse + shape helpers for `history/history_during_period` — no socket, so the time math and
 * numeric mapping are unit-tested in isolation. Mirrors the web `fetchEntityHistory` (`sampleTime`)
 * and the `Sparkline` numeric/discrete level mapping in `src/`.
 */

private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

/** Epoch ms from a sample's `lu`/`lc` (HA sends seconds) or legacy `last_updated`/`last_changed`. */
internal fun sampleTimeMs(sample: JsonObject): Long? {
    (sample.prim("lu") ?: sample.prim("lc"))?.doubleOrNull?.let { return (it * 1000).toLong() }
    val legacy = sample.prim("last_updated") ?: sample.prim("last_changed") ?: return null
    legacy.doubleOrNull?.let { return (it * 1000).toLong() } // numeric seconds
    return legacy.contentOrNull?.let { s ->
        runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()
    }
}

/**
 * Extract one entity's series from the WS `result` map (`{entity_id: [sample, …]}`), sorted by
 * time. Missing entity / malformed samples ⇒ they're dropped (empty series, never a throw).
 */
fun parseHistory(result: JsonObject, entityId: String): List<HistoryPoint> {
    val series = result[entityId] as? JsonArray ?: return emptyList()
    return series.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val state = (o.prim("s") ?: o.prim("state"))?.contentOrNull ?: return@mapNotNull null
        val t = sampleTimeMs(o) ?: return@mapNotNull null
        HistoryPoint(t = t, state = state)
    }.sortedBy { it.t }
}

/**
 * Map a state series to chart levels (mirrors the web `Sparkline`): an all-numeric series renders
 * its numbers directly; a discrete series (lock/binary/cover/…) maps each distinct state to an
 * evenly-spaced index, so on/off/open/locked still draw a readable step line.
 */
fun historyLevels(points: List<HistoryPoint>): List<Float> {
    if (points.isEmpty()) return emptyList()
    val numbers = points.map { it.state.toFloatOrNull() }
    if (numbers.all { it != null }) return numbers.map { it!! }
    val index = points.map { it.state }.distinct().withIndex().associate { (i, s) -> s to i.toFloat() }
    return points.map { index[it.state] ?: 0f }
}
