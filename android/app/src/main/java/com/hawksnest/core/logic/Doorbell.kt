package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import java.time.Instant
import java.time.OffsetDateTime

/** A doorbell press surfaced from a camera's `_ding` sensor. */
data class DoorbellPress(
    val cameraId: String,
    val name: String,
    /** Epoch ms of the press (the ding sensor's last_changed). */
    val whenMs: Long,
)

private fun parseMs(iso: String?, fallback: Long): Long {
    if (iso == null) return fallback
    return runCatching { Instant.parse(iso).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrDefault(fallback)
}

/**
 * The most recent active doorbell press across all cameras — a camera whose
 * `binary_sensor.<base>_ding` is `on` and changed within [windowMs]. ring-mqtt
 * surfaces a doorbell ring as that sensor flipping on. Ported from
 * `src/lib/doorbell.ts`.
 */
fun activeDoorbellPress(
    cameras: List<LogicalCamera>,
    entities: Map<String, HassEntity>,
    nowMs: Long,
    windowMs: Long = 30_000,
): DoorbellPress? {
    var best: DoorbellPress? = null
    for (cam in cameras) {
        val dingId = cam.dingId ?: continue
        val ding = entities[dingId] ?: continue
        if (ding.state != "on") continue
        val whenMs = parseMs(ding.lastChanged, nowMs)
        if (nowMs - whenMs > windowMs) continue
        if (best == null || whenMs > best.whenMs) {
            best = DoorbellPress(cameraId = cam.id, name = cam.name, whenMs = whenMs)
        }
    }
    return best
}
