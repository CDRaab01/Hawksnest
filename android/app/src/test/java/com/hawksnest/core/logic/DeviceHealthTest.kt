package com.hawksnest.core.logic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from `src/lib/__tests__/deviceHealth.test.ts` (Z-Wave diagnostics). */
class DeviceHealthTest {

    @Test
    fun `returns all-null for a device with no z-wave diagnostics`() {
        val h = zwaveHealth(listOf("sensor.front_door_battery" to "80"))
        assertNull(h.nodeStatus)
        assertFalse(h.dead)
        assertNull(h.lastSeenMs)
        assertNull(h.rttMs)
    }

    @Test
    fun `reads node status, last-seen, and round-trip time`() {
        val h = zwaveHealth(
            listOf(
                "sensor.front_door_node_status" to "alive",
                "sensor.front_door_last_seen" to "2023-11-14T22:13:20.000Z",
                "sensor.front_door_round_trip_time" to "42",
            ),
        )
        assertEquals("alive", h.nodeStatus)
        assertFalse(h.dead)
        assertEquals(Instant.parse("2023-11-14T22:13:20.000Z").toEpochMilli(), h.lastSeenMs)
        assertEquals(42, h.rttMs)
    }

    @Test
    fun `flags a dead node`() {
        val h = zwaveHealth(listOf("sensor.back_door_node_status" to "Dead"))
        assertEquals("dead", h.nodeStatus)
        assertTrue(h.dead)
    }

    @Test
    fun `ignores an unavailable node-status sensor`() {
        assertNull(zwaveHealth(listOf("sensor.x_node_status" to "unavailable")).nodeStatus)
    }

    @Test
    fun `identifies the diagnostic entity ids it consumes`() {
        assertTrue(isZWaveDiagnostic("sensor.front_door_node_status"))
        assertTrue(isZWaveDiagnostic("sensor.front_door_last_seen"))
        assertTrue(isZWaveDiagnostic("sensor.front_door_round_trip_time"))
        assertFalse(isZWaveDiagnostic("sensor.front_door_battery"))
    }

    @Test
    fun `controller offline only when every z-wave entity is unavailable`() {
        assertFalse(zwaveControllerOffline(emptyList()))
        assertFalse(zwaveControllerOffline(listOf("locked", "unavailable")))
        assertTrue(zwaveControllerOffline(listOf("unavailable", "unknown", "unavailable")))
    }

    @Test
    fun `relative time formats coarse buckets`() {
        val now = 1_000_000_000_000L
        assertEquals("now", relativeTime(now, now))
        assertEquals("30s ago", relativeTime(now - 30_000, now))
        assertEquals("5m ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("3h ago", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", relativeTime(now - 2 * 86_400_000, now))
    }
}
