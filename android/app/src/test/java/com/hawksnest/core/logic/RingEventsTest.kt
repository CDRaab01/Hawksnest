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

    @Test
    fun `decodes a ring snowflake event id back to its time`() {
        val t = 1_782_925_000_000L
        // Construct an id that encodes t, then confirm the decode round-trips (guards the offset).
        val eid = ((t + 42_790_053_458L) shl 22).toString()
        assertEquals(t, ringEventIdToMs(eid))
        assertEquals(null, ringEventIdToMs(null))
        assertEquals(null, ringEventIdToMs("not-a-number"))
    }

    @Test
    fun `pairs options with real decoded times by recency, oldest-first`() {
        // Motion 1 is newest → takes the newest time; Motion 2 the next.
        val events = ringEventsFromOptions(
            options = listOf("Motion 1", "Motion 2"),
            timesDesc = listOf(5_000L, 1_000L),
            cameraName = "back",
            nowMs = NOW,
        )
        assertEquals(listOf("Motion 2", "Motion 1"), events.map { it.id })
        assertEquals(listOf(1_000L, 5_000L), events.map { it.startMs })
    }

    @Test
    fun `falls back to even spacing when a real time is missing`() {
        val events = ringEventsFromOptions(
            options = listOf("Motion 1", "Motion 2"),
            timesDesc = emptyList(),
            cameraName = "back",
            nowMs = NOW,
        )
        // Motion 1 → NOW, Motion 2 → NOW-6min, sorted oldest-first.
        assertEquals(listOf(NOW - 6 * 60_000L, NOW), events.map { it.startMs })
    }
}
