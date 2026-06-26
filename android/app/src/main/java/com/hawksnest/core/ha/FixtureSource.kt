package com.hawksnest.core.ha

import com.hawksnest.core.logic.LogEvent
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Demo data source — no live HA. Loads the fixtures into [HaState] and simulates service calls
 * locally (the live HA source forwards them and reconciles via the state echo instead). Behind the
 * same [Source] interface as the live source, so no screen changes when we swap.
 */
class FixtureSource @Inject constructor(private val state: HaState) : Source {

    override suspend fun start() {
        state.setBaseUrl("")
        state.setSnapshot(fixtureEntities.associateBy { it.entityId }, fixtureAreaRegistry)
        state.setStatus(ConnectionStatus.DEMO)
    }

    override fun stop() { /* nothing to tear down */ }

    override suspend fun callService(domain: String, service: String, data: ServiceData) {
        val id = data.entityId ?: return
        val current = state.entities.value[id] ?: return
        // Keep the current state when a service only changes attributes (set_percentage, etc.).
        val newState = simulatedState(domain, service, data) ?: current.state
        // Reflect extra service data so the demo's sliders/setpoints move.
        val attrs = current.attributes.toMutableMap()
        data.extra.forEach { (k, v) ->
            if (k == "brightness_pct") {
                (v as? Number)?.let { attrs["brightness"] = anyToJsonElement((it.toDouble() * 2.55).toInt()) }
            } else {
                attrs[k] = anyToJsonElement(v)
            }
        }
        // Mirror the live source's non-optimistic echo: we mutate the store as HA would.
        state.upsertEntities(listOf(current.copy(state = newState, attributes = JsonObject(attrs))))
    }

    /**
     * Synthesize a plausible series so the demo's entity-detail chart renders. Numeric entities get
     * a gentle deterministic wave around their current value; discrete entities hold their state.
     * The last sample matches the live state so the chart's latest point agrees with the card.
     */
    override suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> {
        val current = state.entities.value[entityId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val n = 24
        val stepMs = hours * 3600_000L / n
        val base = current.state.toFloatOrNull()
        return (0 until n).map { i ->
            val t = now - (n - 1 - i) * stepMs
            val s = when {
                i == n - 1 -> current.state // anchor the latest point to the live state
                base != null -> {
                    val wave = base * (1f + 0.06f * kotlin.math.sin(i.toFloat()))
                    ((wave * 10).roundToInt() / 10f).toString()
                }
                else -> current.state
            }
            HistoryPoint(t, s)
        }
    }

    /**
     * Synthesize a short demo logbook so the History tab isn't empty without a live HA — one event
     * per entity (its current state), spaced a few minutes apart and clamped to the window. Real
     * timestamps come from HA in the live source.
     */
    override suspend fun fetchLogbook(startMs: Long, endMs: Long, entityIds: List<String>?): List<LogEvent> {
        val now = System.currentTimeMillis().coerceAtMost(endMs)
        val entities = state.entities.value.values
            .filter { entityIds == null || it.entityId in entityIds }
            .take(20)
        return entities.mapIndexedNotNull { i, e ->
            val t = now - i * 7 * 60_000L
            if (t < startMs) return@mapIndexedNotNull null
            LogEvent(
                timeMs = t,
                name = e.friendlyName() ?: e.entityId,
                message = "changed to ${e.state}",
                entityId = e.entityId,
                domain = domainOf(e.entityId),
                state = e.state,
            )
        }
    }

    /** The resulting state for a simulated `domain.service`, or null to keep the current state. */
    private fun simulatedState(domain: String, service: String, data: ServiceData): String? =
        when (service) {
            "lock" -> "locked"
            "unlock" -> "unlocked"
            "alarm_disarm" -> "disarmed"
            "alarm_arm_home" -> "armed_home"
            "alarm_arm_away" -> "armed_away"
            "alarm_arm_night" -> "armed_night"
            "open_cover" -> "open"
            "close_cover" -> "closed"
            "turn_on" -> "on"
            "turn_off" -> "off"
            "media_play_pause" -> if (state.entities.value[data.entityId]?.state == "playing") "paused" else "playing"
            else -> null
        }
}
