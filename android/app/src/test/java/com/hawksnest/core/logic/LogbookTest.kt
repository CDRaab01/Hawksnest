package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ports the web `logbook.test.ts` suite 1:1 (HA payload → normalized, newest-first events). */
class LogbookTest {

    private fun entries(json: String): List<JsonObject> =
        (Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray).map { it.jsonObject }

    @Test
    fun `converts epoch-second when to ms and sorts newest-first`() {
        val events = normalizeLogbook(
            entries(
                """
                [
                  {"when": 1700000000, "name": "A", "message": "older", "entity_id": "lock.a"},
                  {"when": 1700000060, "name": "B", "message": "newer", "entity_id": "lock.b"}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("B", "A"), events.map { it.name })
        assertEquals(1_700_000_000_000L, events[1].timeMs)
    }

    @Test
    fun `derives domain from the entity_id`() {
        val evt = normalizeLogbook(
            entries("""[{"when": 1700000000, "entity_id": "binary_sensor.front_door_motion"}]"""),
        ).first()
        assertEquals("binary_sensor", evt.domain)
        assertEquals("binary_sensor.front_door_motion", evt.name)
    }

    @Test
    fun `synthesizes a message from state when none is given`() {
        val evt = normalizeLogbook(
            entries("""[{"when": 1700000000, "name": "Lamp", "state": "on"}]"""),
        ).first()
        assertEquals("changed to on", evt.message)
    }

    @Test
    fun `drops entries with no usable timestamp`() {
        assertEquals(0, normalizeLogbook(entries("""[{"name": "no when"}]""")).size)
    }
}
