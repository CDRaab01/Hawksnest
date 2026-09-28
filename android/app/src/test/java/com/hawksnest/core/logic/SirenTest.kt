package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SirenTest {

    @Test
    fun `the house's siren switches are sirens`() {
        // The three enabled siren switches in the live registry (2026-09-28).
        assertTrue(isSirenSwitch("switch.mfa_alarm_siren"))
        assertTrue(isSirenSwitch("switch.office_siren"))
        assertTrue(isSirenSwitch("switch.pet_camera_siren"))
    }

    @Test
    fun `Reolink's siren-on-event config toggle and the siren domain are not siren switches`() {
        assertFalse(isSirenSwitch("switch.kitchen_siren_on_event"))
        assertFalse(isSirenSwitch("siren.garage_siren"))
        assertFalse(isSirenSwitch("light.siren_lamp"))
        assertFalse(isSirenSwitch("switch.porch"))
    }

    @Test
    fun `siren switches get their own card kind, other switches stay switches`() {
        assertEquals(CardType.SIREN, domainToCard("switch.pet_camera_siren"))
        assertEquals(CardType.SWITCH, domainToCard("switch.kitchen_siren_on_event"))
        assertEquals(CardType.SWITCH, domainToCard("switch.porch"))
    }

    @Test
    fun `two taps to sound, one to silence`() {
        assertEquals(SirenTap.ARM, sirenTap(on = false, armed = false))
        assertEquals(SirenTap.SOUND, sirenTap(on = false, armed = true))
        assertEquals(SirenTap.SILENCE, sirenTap(on = true, armed = false))
        // Silencing never waits on a confirm, even mid-arm.
        assertEquals(SirenTap.SILENCE, sirenTap(on = true, armed = true))
    }

    @Test
    fun `sirens are a control tier, not read-only inventory`() {
        assertEquals(DeviceTier.CONTROL, tierOf(CardType.SIREN))
    }
}
