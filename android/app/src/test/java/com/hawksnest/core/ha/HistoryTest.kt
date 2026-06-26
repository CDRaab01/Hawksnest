package com.hawksnest.core.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the pure history parse + level-mapping helpers (no socket). */
class HistoryTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `parseHistory reads compressed samples and sorts by time`() {
        // minimal_response: full first sample, then {s, lu} deltas; lu is epoch seconds.
        val result = obj(
            """
            {
              "sensor.temp": [
                {"s": "21.0", "lu": 1000},
                {"s": "21.5", "lu": 2000},
                {"s": "20.5", "lu": 1500}
              ]
            }
            """.trimIndent(),
        )
        val points = parseHistory(result, "sensor.temp")
        assertEquals(3, points.size)
        assertEquals(listOf(1_000_000L, 1_500_000L, 2_000_000L), points.map { it.t })
        assertEquals(listOf("21.0", "20.5", "21.5"), points.map { it.state })
    }

    @Test
    fun `parseHistory falls back to legacy keys and tolerates missing entity`() {
        val result = obj(
            """
            {
              "lock.front": [
                {"state": "locked", "last_updated": "2026-06-26T00:00:00Z"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, parseHistory(result, "lock.front").size)
        assertEquals("locked", parseHistory(result, "lock.front").first().state)
        assertTrue(parseHistory(result, "sensor.absent").isEmpty())
    }

    @Test
    fun `historyLevels passes numeric series through`() {
        val pts = listOf(HistoryPoint(1, "21.0"), HistoryPoint(2, "22.5"))
        assertEquals(listOf(21.0f, 22.5f), historyLevels(pts))
    }

    @Test
    fun `historyLevels maps discrete states to evenly-spaced indices`() {
        val pts = listOf(
            HistoryPoint(1, "locked"),
            HistoryPoint(2, "unlocked"),
            HistoryPoint(3, "locked"),
        )
        // distinct order: locked=0, unlocked=1
        assertEquals(listOf(0f, 1f, 0f), historyLevels(pts))
    }
}
