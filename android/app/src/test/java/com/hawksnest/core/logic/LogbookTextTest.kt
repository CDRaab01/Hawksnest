package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogbookTextTest {

    @Test
    fun `binary sensors are phrased for what they detect`() {
        assertEquals("Motion detected", describeStateChange("binary_sensor", "motion", "on"))
        assertEquals("Motion cleared", describeStateChange("binary_sensor", "occupancy", "off"))
        assertEquals("Opened", describeStateChange("binary_sensor", "garage_door", "on"))
        assertEquals("Closed", describeStateChange("binary_sensor", "door", "off"))
        assertEquals("Leak detected", describeStateChange("binary_sensor", "moisture", "on"))
        assertEquals("Turned on", describeStateChange("binary_sensor", null, "on"))
    }

    @Test
    fun `the lock device class is inverted — on means unlocked`() {
        assertEquals("Unlocked", describeStateChange("binary_sensor", "lock", "on"))
        assertEquals("Locked", describeStateChange("binary_sensor", "lock", "off"))
    }

    @Test
    fun `security domains say what happened`() {
        assertEquals("Locked", describeStateChange("lock", null, "locked"))
        assertEquals("Jammed", describeStateChange("lock", null, "jammed"))
        assertEquals("Armed away", describeStateChange("alarm_control_panel", null, "armed_away"))
        assertEquals("Entry delay started", describeStateChange("alarm_control_panel", null, "pending"))
        assertEquals("Alarm triggered", describeStateChange("alarm_control_panel", null, "triggered"))
    }

    @Test
    fun `offline and readings`() {
        assertEquals("Went offline", describeStateChange("light", null, "unavailable"))
        assertEquals("21.5 °C", describeStateChange("sensor", "temperature", "21.5", "°C"))
        assertNull(describeStateChange("sensor", null, "NONE"))
        assertNull(describeStateChange("select", null, "Preset 3"))
        assertNull(describeStateChange("light", null, null))
        assertEquals("Left home", describeStateChange("person", null, "not_home"))
    }

    @Test
    fun `category chips use household words`() {
        assertEquals("Sensors", historyCategoryLabel("binary_sensor"))
        assertEquals("Alarm", historyCategoryLabel("alarm_control_panel"))
        assertEquals("Input Number", historyCategoryLabel("input_number"))
    }
}
