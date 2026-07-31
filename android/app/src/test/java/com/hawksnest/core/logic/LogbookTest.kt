package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // ── The cap that stops a month of logbook taking the app down ─────────────────────────────

    private fun ev(t: Long) = LogEvent(t, "n", "m", null, null, null)

    @Test
    fun `passes a small feed through untouched and says nothing was dropped`() {
        val events = listOf(ev(3), ev(2), ev(1))
        assertEquals(LogbookFeed(events, false), capLogbook(events, 10))
    }

    @Test
    fun `keeps the NEWEST events, not the oldest`() {
        // normalizeLogbook sorts newest-first, so the cap has to take from the front. Taking the
        // tail would silently show a month-old window and look like history stopped updating.
        val capped = capLogbook(listOf(ev(5), ev(4), ev(3), ev(2), ev(1)), 2)
        assertEquals(listOf(5L, 4L), capped.events.map { it.timeMs })
        assertTrue(capped.truncated)
    }

    @Test
    fun `reports truncation only when something was actually dropped`() {
        val events = listOf(ev(2), ev(1))
        assertFalse(capLogbook(events, 2).truncated)
        assertTrue(capLogbook(events, 1).truncated)
    }

    @Test
    fun `survives a zero limit without returning a partial lie`() {
        assertEquals(LogbookFeed(emptyList(), true), capLogbook(listOf(ev(1)), 0))
        assertEquals(LogbookFeed(emptyList(), false), capLogbook(emptyList(), 0))
    }

    @Test
    fun `default limit is far below a single day of this instance's traffic`() {
        // ~98,000 recorder rows/day measured against the live MariaDB — the default has to be far
        // below that or the cap is decorative.
        assertTrue(LOGBOOK_MAX_EVENTS in 1 until 5000)
    }
}
