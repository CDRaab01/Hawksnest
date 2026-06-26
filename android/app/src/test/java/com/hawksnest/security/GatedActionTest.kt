package com.hawksnest.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the gated set: only "make the home less secure" actions require biometric confirmation. */
class GatedActionTest {

    @Test
    fun `unlock and disarm are gated`() {
        assertTrue(isSensitiveAction("lock", "unlock"))
        assertTrue(isSensitiveAction("alarm_control_panel", "alarm_disarm"))
    }

    @Test
    fun `making the home more secure is never gated`() {
        assertFalse(isSensitiveAction("lock", "lock"))
        assertFalse(isSensitiveAction("alarm_control_panel", "alarm_arm_away"))
        assertFalse(isSensitiveAction("alarm_control_panel", "alarm_arm_home"))
    }

    @Test
    fun `everyday controls are never gated`() {
        assertFalse(isSensitiveAction("light", "turn_on"))
        assertFalse(isSensitiveAction("switch", "turn_off"))
        assertFalse(isSensitiveAction("cover", "open_cover"))
    }
}
