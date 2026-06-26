package com.hawksnest.core.ha

import com.hawksnest.core.logic.LogEvent

/** One historical sample for an entity. [t] is epoch milliseconds; [state] is the raw HA state. */
data class HistoryPoint(val t: Long, val state: String)

/** Optional service-call data. `entity_id` targets the entity; the rest is service data. */
data class ServiceData(
    val entityId: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
)

/**
 * A data Source feeds the entity store and (optionally) carries out control actions. The fixture
 * source simulates everything locally; the live HA source (WebSocket) lands behind the same
 * interface, so no screen or card changes when we swap. Ported from `src/store/source.ts`.
 */
interface Source {
    suspend fun start()
    fun stop()

    /**
     * Perform an HA service call (e.g. lock.lock, light.turn_on). The fixture source simulates it
     * locally; the live source forwards it to HA, whose state echo reconciles the store —
     * **non-optimistic**. Throws if the source can't perform writes.
     */
    suspend fun callService(domain: String, service: String, data: ServiceData = ServiceData()) {
        throw UnsupportedOperationException("This source cannot perform writes.")
    }

    /** Fetch recent state history for one entity over the last [hours]. */
    suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> {
        throw UnsupportedOperationException("This source cannot provide history.")
    }

    /** Fetch the logbook over `[startMs, endMs]`, optionally narrowed to specific entities. */
    suspend fun fetchLogbook(startMs: Long, endMs: Long, entityIds: List<String>? = null): List<LogEvent> {
        throw UnsupportedOperationException("This source cannot provide a logbook.")
    }
}
