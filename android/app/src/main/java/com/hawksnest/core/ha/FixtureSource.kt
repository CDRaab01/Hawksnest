package com.hawksnest.core.ha

import javax.inject.Inject

/**
 * Demo data source — no live HA. Loads the fixtures into [HaState] and simulates service calls
 * locally (the live HA source forwards them and reconciles via the state echo instead). Behind the
 * same [Source] interface as the live source, so no screen changes when we swap.
 *
 * Ported from `src/store/fixtureSource.ts`.
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
        val newState = simulatedState(domain, service) ?: return
        // Mirror the live source's non-optimistic echo: we mutate the store as HA would.
        state.upsertEntities(listOf(current.copy(state = newState)))
    }

    /** The resulting state for a simulated `domain.service`, or null if we don't model it. */
    private fun simulatedState(domain: String, service: String): String? = when (service) {
        "lock" -> "locked"
        "unlock" -> "unlocked"
        "alarm_disarm" -> "disarmed"
        "alarm_arm_home" -> "armed_home"
        "alarm_arm_away" -> "armed_away"
        "alarm_arm_night" -> "armed_night"
        "turn_on", "open" -> if (domain == "cover") "open" else "on"
        "turn_off", "close" -> if (domain == "cover") "closed" else "off"
        else -> null
    }
}
