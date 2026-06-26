package com.hawksnest.core.logic

import com.hawksnest.core.ha.domainOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToLong

/**
 * One normalized logbook event. (`when` is a Kotlin keyword, so the epoch field is [timeMs].)
 * Ported from `src/lib/logbook.ts`.
 */
data class LogEvent(
    /** Epoch milliseconds. */
    val timeMs: Long,
    /** Human subject, e.g. "Front Door" (HA's `name`). */
    val name: String,
    /** Human message, e.g. "was opened" / "turned on". */
    val message: String,
    val entityId: String?,
    val domain: String?,
    val state: String?,
)

private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull

/** HA sends `when` as epoch *seconds* (float) or, on older builds, an ISO string. Normalize to ms. */
private fun whenMs(p: JsonPrimitive?): Long {
    if (p == null) return 0
    p.doubleOrNull?.let { return (it * 1000).roundToLong() }
    val s = p.contentOrNull ?: return 0
    return runCatching { Instant.parse(s).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .getOrDefault(0)
}

/** Best-effort human message when HA omits one. */
private fun messageFor(e: JsonObject): String {
    e.str("message")?.let { return it }
    e.str("state")?.let { return "changed to $it" }
    return "changed"
}

/**
 * Normalize HA's `logbook/get_events` payload into typed, newest-first events. Entries with no
 * usable timestamp are dropped. The live + fixture sources both return this shape so the History
 * screen is source-agnostic. Ported from `src/lib/logbook.ts normalizeLogbook`.
 */
fun normalizeLogbook(raw: List<JsonObject>): List<LogEvent> =
    raw.map { e ->
        val entityId = e.str("entity_id")
        val domain = if (entityId != null) domainOf(entityId) else (e.str("domain") ?: e.str("context_domain"))
        LogEvent(
            timeMs = whenMs(e.prim("when")),
            name = e.str("name") ?: entityId ?: "Unknown",
            message = messageFor(e),
            entityId = entityId,
            domain = domain,
            state = e.str("state"),
        )
    }.filter { it.timeMs > 0 }.sortedByDescending { it.timeMs }
