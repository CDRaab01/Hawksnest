package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ports `ringEvents.ts` behavior. */
class RingEventsTest {

    private val NOW = 1_700_000_000_000L

    private fun select(vararg options: String): HassEntity = HassEntity(
        entityId = "select.front_event_select",
        state = options.firstOrNull() ?: "",
        attributes = buildJsonObject {
            putJsonArray("options") { options.forEach { add(it) } }
        },
    )

    @Test
    fun `parses options into labelled events, oldest-first`() {
        val events = ringEventsFromSelect(
            select("Motion 1", "Ding 2", "On-Demand 3"),
            "front",
            NOW,
        )
        assertEquals(3, events.size)
        // Spaced back from now by index, then sorted oldest-first → reversed.
        assertEquals(listOf("On-Demand 3", "Ding 2", "Motion 1"), events.map { it.id })
        assertEquals(setOf("motion", "ding", "event"), events.map { it.label }.toSet())
        events.forEach { assertEquals("front", it.camera) }
    }

    @Test
    fun `returns empty when there are no options`() {
        assertTrue(ringEventsFromSelect(null, "front", NOW).isEmpty())
        val noOptions = HassEntity("select.x", "", JsonObject(emptyMap()))
        assertTrue(ringEventsFromSelect(noOptions, "front", NOW).isEmpty())
    }
}
