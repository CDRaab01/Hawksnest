package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the pure offline model (grace window, retry countdown, "as of" formatting, and
 * the lock/alarm stale-state mask). Mirrors `src/lib/__tests__/offline.test.ts` on the web.
 */
class OfflineTest {

    private fun entity(id: String, state: String) = HassEntity(entityId = id, state = state)

    // ── grace window ─────────────────────────────────────────────────────────────────────────

    @Test
    fun graceWindow_holdsJustUnderTwoMinutes_expiresAtTheBoundary() {
        val t0 = 1_700_000_000_000L
        assertFalse(graceExpired(t0, t0))
        assertFalse(graceExpired(t0, t0 + GRACE_WINDOW_MS - 1))
        assertTrue(graceExpired(t0, t0 + GRACE_WINDOW_MS))
        assertTrue(graceExpired(t0, t0 + GRACE_WINDOW_MS + 1))
    }

    // ── retry countdown ──────────────────────────────────────────────────────────────────────

    @Test
    fun retryCountdown_roundsUp_andNeverGoesNegative() {
        val now = 1_700_000_000_000L
        assertEquals(5, retryCountdownSeconds(now + 5_000, now))
        assertEquals(5, retryCountdownSeconds(now + 4_001, now))
        assertEquals(1, retryCountdownSeconds(now + 1, now))
        assertEquals(0, retryCountdownSeconds(now, now))
        assertEquals(0, retryCountdownSeconds(now - 3_000, now)) // attempt already due
    }

    // ── "as of" formatting ───────────────────────────────────────────────────────────────────

    @Test
    fun formatAsOf_sameDay_isClockOnly() {
        // 2023-11-14T15:42 UTC; "now" later the same UTC day.
        val then = 1_699_976_520_000L
        val now = then + 3_600_000
        assertEquals("3:42 PM", formatAsOf(then, now, ZoneOffset.UTC))
    }

    @Test
    fun formatAsOf_otherDay_carriesTheDate() {
        val then = 1_699_976_520_000L // Nov 14, 3:42 PM UTC
        val now = then + 26 * 3_600_000L // next day
        assertEquals("Nov 14, 3:42 PM", formatAsOf(then, now, ZoneOffset.UTC))
    }

    // ── security-state mask (the "never render a stale lock" invariant) ──────────────────────

    @Test
    fun mask_collapsesLockAndAlarm_leavesEverythingElse() {
        val masked = maskSecurityStates(
            mapOf(
                "lock.front" to entity("lock.front", "locked"),
                "lock.back" to entity("lock.back", "jammed"),
                "alarm_control_panel.home" to entity("alarm_control_panel.home", "armed_home"),
                "light.porch" to entity("light.porch", "on"),
                "binary_sensor.back_door" to entity("binary_sensor.back_door", "off"),
            ),
        )
        assertEquals("unavailable", masked["lock.front"]!!.state)
        assertEquals("unavailable", masked["lock.back"]!!.state)
        assertEquals("unavailable", masked["alarm_control_panel.home"]!!.state)
        assertEquals("on", masked["light.porch"]!!.state)
        assertEquals("off", masked["binary_sensor.back_door"]!!.state)
    }

    @Test
    fun mask_preservesAttributes() {
        val lock = HassEntity(
            entityId = "lock.front",
            state = "locked",
            attributes = buildJsonObject { put("friendly_name", "Front Door") },
        )
        val masked = maskSecurityStates(mapOf("lock.front" to lock))
        assertEquals(
            "Front Door",
            (masked["lock.front"]!!.attributes["friendly_name"] as JsonPrimitive).content,
        )
    }

    @Test
    fun mask_isIdentity_whenNothingNeedsMasking() {
        val entities = mapOf(
            "light.porch" to entity("light.porch", "on"),
            "lock.front" to entity("lock.front", "unavailable"), // already honest
        )
        assertSame(entities, maskSecurityStates(entities)) // no spurious flow emission
    }
}
