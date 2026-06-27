package com.hawksnest.core.logic

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Z-Wave node diagnostics surfaced on a device's detail. Ported from web
 * `src/lib/deviceHealth.ts` (`zwaveHealth`).
 */
data class ZWaveHealth(
    /** Node lifecycle ("alive" | "awake" | "asleep" | "dead"), or null if not a Z-Wave node. */
    val nodeStatus: String?,
    /** Controller can't reach the node — surfaced as a warning on the device. */
    val dead: Boolean,
    /** Epoch ms the controller last heard from the node, when reported. */
    val lastSeenMs: Long?,
    /** Round-trip time to the node in ms (lower is better); null unless the stat is enabled. */
    val rttMs: Int?,
)

// "dead" means the controller can no longer reach the node (e.g. a lock dropped
// off the mesh) — the one node-status value that's actionable.
private const val DEAD_NODE_STATUS = "dead"
private const val NODE_STATUS_SUFFIX = "_node_status"
private const val LAST_SEEN_SUFFIX = "_last_seen"
private const val RTT_SUFFIX = "_round_trip_time"
private val OFFLINE_STATES = setOf("unavailable", "unknown", "none", "")

/** True if `entityId` is one of the Z-Wave diagnostic entities `zwaveHealth` reads. */
fun isZWaveDiagnostic(entityId: String): Boolean =
    entityId.endsWith(NODE_STATUS_SUFFIX) ||
        entityId.endsWith(LAST_SEEN_SUFFIX) ||
        entityId.endsWith(RTT_SUFFIX)

private fun parseIsoMs(s: String): Long? =
    runCatching { Instant.parse(s).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .getOrNull()

/**
 * Pull Z-Wave node diagnostics from a device's sibling entities, each a pair of
 * (entityId → raw state). HA's Z-Wave JS integration exposes these as per-device
 * diagnostic entities: node status (`*_node_status`), last-seen (`*_last_seen`),
 * and — when network statistics are enabled — round-trip time
 * (`*_round_trip_time`). Returns all-null for a non-Z-Wave device, so callers can
 * render the panel only when there's data.
 */
fun zwaveHealth(siblings: List<Pair<String, String>>): ZWaveHealth {
    var nodeStatus: String? = null
    var lastSeenMs: Long? = null
    var rttMs: Int? = null
    for ((id, rawState) in siblings) {
        when {
            id.endsWith(NODE_STATUS_SUFFIX) -> {
                val v = rawState.lowercase()
                if (v !in OFFLINE_STATES) nodeStatus = v
            }
            id.endsWith(LAST_SEEN_SUFFIX) -> parseIsoMs(rawState)?.let { lastSeenMs = it }
            id.endsWith(RTT_SUFFIX) -> rawState.toIntOrNull()?.let { rttMs = it }
        }
    }
    return ZWaveHealth(nodeStatus, nodeStatus == DEAD_NODE_STATUS, lastSeenMs, rttMs)
}

/**
 * True when the Z-Wave controller/radio looks offline: there are Z-Wave entity
 * states and *every one* is `unavailable`/`unknown`. A single dead node leaves the
 * rest reporting, so this only fires when the whole network drops at once — the
 * symptom of the stick or zwave-js-ui going down. Mirrors web `zwaveControllerOffline`.
 */
fun zwaveControllerOffline(states: List<String>): Boolean {
    if (states.isEmpty()) return false
    return states.all { it.lowercase() in OFFLINE_STATES }
}

/**
 * Compact "x ago" label from an epoch-ms timestamp. Ported from web
 * `src/lib/relativeTime.ts`; `now` is injectable so it's deterministic in tests.
 */
fun relativeTime(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - thenMs).coerceAtLeast(0)
    val s = diff / 1000
    if (s < 5) return "now"
    if (s < 60) return "${s}s ago"
    val m = s / 60
    if (m < 60) return "${m}m ago"
    val h = m / 60
    if (h < 24) return "${h}h ago"
    val d = h / 24
    if (d < 7) return "${d}d ago"
    val w = d / 7
    return "${w}w ago"
}
