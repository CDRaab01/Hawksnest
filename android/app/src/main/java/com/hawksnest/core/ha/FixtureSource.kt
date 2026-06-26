package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

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
