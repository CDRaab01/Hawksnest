package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The thermostat dial's view-model and arc geometry. */
class ThermostatTest {

    private fun attrs(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    // thermostatView

    @Test
    fun `full attributes build an adjustable dial`() {
        val v = thermostatView(
            attrs(
                "temperature" to 72.0, "current_temperature" to 68.5,
                "min_temp" to 45.0, "max_temp" to 95.0, "target_temp_step" to 1.0,
                "unit_of_measurement" to "°F", "hvac_action" to "heating",
            ),
            "heat",
        )
        assertEquals(72.0, v.target)
        assertEquals(68.5, v.current)
        assertEquals(45.0, v.min)
        assertEquals(95.0, v.max)
        assertEquals(1.0, v.step)
        assertEquals("°F", v.unit)
        assertEquals(Channel.STREAK, v.channel)
        assertTrue(v.adjustable)
    }

    @Test
    fun `missing min-max falls back to target plus-minus ten`() {
        val v = thermostatView(attrs("temperature" to 70.0), "heat")
        assertEquals(60.0, v.min)
        assertEquals(80.0, v.max)
        assertTrue(v.adjustable)
    }

    @Test
    fun `no target means a read-only dial`() {
        val v = thermostatView(attrs("current_temperature" to 68.0), "auto")
        assertNull(v.target)
        assertFalse(v.adjustable)
    }

    @Test
    fun `unavailable and off are not adjustable`() {
        assertFalse(thermostatView(attrs("temperature" to 70.0), "unavailable").adjustable)
        assertFalse(thermostatView(attrs("temperature" to 70.0), "off").adjustable)
    }

    @Test
    fun `hvac_action picks the channel — heating warm, cooling cool, idle neutral`() {
        assertEquals(Channel.STREAK, thermostatView(attrs("temperature" to 70.0, "hvac_action" to "heating"), "heat").channel)
        assertEquals(Channel.EFFORT, thermostatView(attrs("temperature" to 70.0, "hvac_action" to "cooling"), "cool").channel)
        assertNull(thermostatView(attrs("temperature" to 70.0, "hvac_action" to "idle"), "heat").channel)
        assertNull(thermostatView(attrs("temperature" to 70.0), "heat").channel)
    }

    @Test
    fun `defaults — half-degree step and bare degree unit`() {
        val v = thermostatView(attrs("temperature" to 21.0), "heat")
        assertEquals(0.5, v.step)
        assertEquals("°", v.unit)
    }

    // arc math

    @Test
    fun `temp maps to fraction and back, snapped to step`() {
        assertEquals(0.5f, tempToFraction(70.0, 45.0, 95.0), 0.001f)
        assertEquals(70.0, fractionToTemp(0.5f, 45.0, 95.0, 1.0), 0.001)
        assertEquals(70.5, fractionToTemp(0.51f, 45.0, 95.0, 0.5), 0.001)
    }

    @Test
    fun `fraction round-trip survives clamping at the ends`() {
        assertEquals(0f, tempToFraction(40.0, 45.0, 95.0), 0.001f)
        assertEquals(1f, tempToFraction(99.0, 45.0, 95.0), 0.001f)
        assertEquals(45.0, fractionToTemp(-0.2f, 45.0, 95.0, 1.0), 0.001)
        assertEquals(95.0, fractionToTemp(1.2f, 45.0, 95.0, 1.0), 0.001)
    }

    @Test
    fun `degenerate range is safe`() {
        assertEquals(0f, tempToFraction(70.0, 70.0, 70.0), 0.001f)
    }

    // touchToFraction — center at (0,0); Canvas angles: 0° at 3 o'clock, clockwise, y down

    @Test
    fun `arc start bottom-left is 0, arc end bottom-right is 1`() {
        assertEquals(0f, touchToFraction(-10f, 10f, 0f, 0f)!!, 0.01f)   // 135°
        assertEquals(1f, touchToFraction(10f, 10f, 0f, 0f)!!, 0.01f)    // 45°
    }

    @Test
    fun `top of the dial is the midpoint`() {
        assertEquals(0.5f, touchToFraction(0f, -10f, 0f, 0f)!!, 0.01f)  // 270°
    }

    @Test
    fun `the bottom dead gap rejects touches`() {
        assertNull(touchToFraction(0f, 10f, 0f, 0f))                    // 90°, straight down
    }

    // fmtTemp

    @Test
    fun `whole temps stay whole, halves keep one decimal`() {
        assertEquals("72", fmtTemp(72.0))
        assertEquals("71.5", fmtTemp(71.5))
    }
}
