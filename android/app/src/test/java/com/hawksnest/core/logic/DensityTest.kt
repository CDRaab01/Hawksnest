package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from `src/lib/__tests__/density.test.ts`. */
class DensityTest {

    @Test
    fun `controls render comfortable`() {
        assertEquals(Density.COMFORTABLE, cardDensityFor("lock.front_door_lock"))
        assertEquals(Density.COMFORTABLE, cardDensityFor("light.basement"))
        assertEquals(Density.COMFORTABLE, cardDensityFor("alarm_control_panel.home"))
    }

    @Test
    fun `read-only entities render compact`() {
        assertEquals(Density.COMPACT, cardDensityFor("sensor.front_door_battery"))
        assertEquals(Density.COMPACT, cardDensityFor("binary_sensor.front_door_current_status"))
    }

    @Test
    fun `camera is a comfortable feature tile`() {
        assertEquals(Density.COMFORTABLE, cardDensityFor("camera.front_door"))
        assertTrue(isFeature("camera.front_door"))
        assertFalse(isFeature("lock.front_door_lock"))
    }
}
