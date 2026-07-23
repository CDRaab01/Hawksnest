package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from `src/lib/__tests__/alarm.test.ts`. */
class AlarmTest {

    @Test
    fun `disarmed is settled (recovery, shield-check)`() {
        val v = alarmView("disarmed")
        assertEquals("Disarmed", v.label)
        assertEquals("Disarmed", v.short)
        assertEquals(Channel.RECOVERY, v.channel)
        assertEquals(AlarmGlyph.SHIELD_CHECK, v.glyph)
        assertFalse(v.armed)
        assertFalse(v.triggered)
        assertFalse(v.transitioning)
    }

    @Test
    fun `any armed_ state is armed (effort, shield)`() {
        for (s in listOf("armed_home", "armed_away", "armed_night")) {
            val v = alarmView(s)
            assertTrue(v.armed)
            assertEquals(Channel.EFFORT, v.channel)
            assertEquals(AlarmGlyph.SHIELD, v.glyph)
        }
        assertEquals("Home", alarmView("armed_home").short)
        assertEquals("Away", alarmView("armed_away").short)
    }

    @Test
    fun `triggered is an alert (streak, shield-alert)`() {
        val v = alarmView("triggered")
        assertTrue(v.triggered)
        assertEquals(Channel.STREAK, v.channel)
        assertEquals(AlarmGlyph.SHIELD_ALERT, v.glyph)
    }

    @Test
    fun `flags transitional states`() {
        assertTrue(alarmView("arming").transitioning)
        assertTrue(alarmView("pending").transitioning)
        assertTrue(alarmView("disarming").transitioning)
    }

    @Test
    fun `falls back to the raw state for unknown values`() {
        assertEquals("custom_bypass", alarmView("custom_bypass").label)
    }

    @Test
    fun `transitional set matches the transitioning flag`() {
        for (s in ALARM_TRANSITIONAL) assertTrue(alarmView(s).transitioning)
        assertFalse("disarmed" in ALARM_TRANSITIONAL)
        assertFalse("armed_away" in ALARM_TRANSITIONAL)
    }

    @Test
    fun `exposes Off Home Away with their services`() {
        assertEquals(
            listOf("alarm_disarm", "alarm_arm_home", "alarm_arm_away"),
            ARM_BUTTONS.map { it.service },
        )
    }
}
