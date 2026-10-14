package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.haTimeMs

/** A doorbell press surfaced from a camera's ding sensor (`_ding` or `_visitor`). */
data class DoorbellPress(
    val cameraId: String,
    val name: String,
    /** Epoch ms of the press (the ding sensor's last_changed). */
    val whenMs: Long,
)

/**
 * The most recent active doorbell press across all cameras — a camera whose
 * resolved ding sensor is `on` and changed within [windowMs]. ring-mqtt surfaces a
 * ring as `binary_sensor.<base>_ding` and the Reolink integration as `_visitor`;
 * [resolveCameras] folds both into [LogicalCamera.dingId]. Ported from
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
        // Websocket updates carry last_changed as epoch seconds, REST as ISO; haTimeMs reads both.
        // Only an absent time falls back to "now", and that is a sensor HA gave no time for at all.
        val whenMs = haTimeMs(ding.lastChanged) ?: nowMs
        if (nowMs - whenMs > windowMs) continue
        if (best == null || whenMs > best.whenMs) {
            best = DoorbellPress(cameraId = cam.id, name = cam.name, whenMs = whenMs)
        }
    }
    return best
}
