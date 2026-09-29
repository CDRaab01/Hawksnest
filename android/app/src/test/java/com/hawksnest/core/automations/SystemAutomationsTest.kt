package com.hawksnest.core.automations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemAutomationsTest {

    @Test
    fun `the automations hawksnest-automation deploys are system ones`() {
        assertTrue(isSystemAutomation("Hawksnest: park the Home Hub battery cameras in Frigate"))
        assertTrue(isSystemAutomation("Hawksnest push: doorbell ding"))
        assertTrue(isSystemAutomation("hawksnest: Frigate detection watchdog"))
    }

    @Test
    fun `household automations are not`() {
        assertFalse(isSystemAutomation("Nursery: fan follows temperature"))
        assertFalse(isSystemAutomation("All Lock"))
        assertFalse(isSystemAutomation("Hawksnest nightlight"))
    }
}
