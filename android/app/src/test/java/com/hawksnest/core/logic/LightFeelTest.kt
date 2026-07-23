package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The light pillar's feel — drag mapping, haptic ticks, warmth, and the release commit. */
class LightFeelTest {

    private fun attrs(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as Number) })
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    // dragToPct

    @Test
    fun `dragging up brightens, dragging down dims`() {
        // Compose deltas grow downward; -100px on a 400px track = +25 points.
        assertEquals(75, dragToPct(50, -100f, 400f))
        assertEquals(25, dragToPct(50, 100f, 400f))
    }

    @Test
    fun `drag clamps to 0 and 100`() {
        assertEquals(100, dragToPct(90, -400f, 400f))
        assertEquals(0, dragToPct(10, 400f, 400f))
    }

    @Test
    fun `an unmeasured track leaves the value unchanged`() {
        assertEquals(50, dragToPct(50, -100f, 0f))
    }

    // tickCrossed

    @Test
    fun `crossing one tick reports it`() {
        assertEquals(50, tickCrossed(48, 52))
        assertEquals(50, tickCrossed(52, 48))
    }

    @Test
    fun `a fast flick reports only the tick nearest the landing value`() {
        assertEquals(75, tickCrossed(17, 80))
        assertEquals(25, tickCrossed(80, 17))
    }

    @Test
    fun `landing exactly on a tick counts going up but not leaving it`() {
        assertEquals(75, tickCrossed(74, 75))
        assertNull(tickCrossed(75, 74)) // 75 was already ticked; 74 crosses nothing
        assertEquals(75, tickCrossed(76, 75))
    }

    @Test
    fun `no movement or no tick in range is silent`() {
        assertNull(tickCrossed(30, 30))
        assertNull(tickCrossed(26, 49))
    }

    @Test
    fun `the floor and ceiling are ticks`() {
        assertEquals(0, tickCrossed(10, 0))
        assertEquals(100, tickCrossed(90, 100))
    }

    // brightnessPct

    @Test
    fun `brightness attribute maps 0-255 to percent, absent reads 0`() {
        assertEquals(50, brightnessPct(attrs("brightness" to 128)))
        assertEquals(100, brightnessPct(attrs("brightness" to 255)))
        assertEquals(0, brightnessPct(JsonObject(emptyMap())))
    }

    // lightWarmth

    @Test
    fun `kelvin warmth uses the light's own range when reported`() {
        val a = attrs("color_temp_kelvin" to 3000, "min_color_temp_kelvin" to 2000, "max_color_temp_kelvin" to 4000)
        assertEquals(0.5f, lightWarmth(a)!!, 0.001f)
    }

    @Test
    fun `kelvin warmth falls back to the 2000-6500 default range`() {
        assertEquals(1f, lightWarmth(attrs("color_temp_kelvin" to 2000))!!, 0.001f)
        assertEquals(0f, lightWarmth(attrs("color_temp_kelvin" to 6500))!!, 0.001f)
    }

    @Test
    fun `rgb fallback reads red-heavy as warm, blue-heavy as cool`() {
        assertEquals(1f, lightWarmth(attrs("rgb_color" to listOf(255, 160, 0)))!!, 0.001f)
        assertEquals(0f, lightWarmth(attrs("rgb_color" to listOf(0, 160, 255)))!!, 0.001f)
        assertEquals(0.5f, lightWarmth(attrs("rgb_color" to listOf(255, 255, 255)))!!, 0.001f)
    }

    @Test
    fun `no color info means no warmth`() {
        assertNull(lightWarmth(JsonObject(emptyMap())))
        assertNull(lightWarmth(attrs("brightness" to 128)))
    }

    // washAlpha — constants pinned to the web LightCard so both clients glow alike

    @Test
    fun `wash scales with brightness and vanishes when off`() {
        assertEquals(0f, washAlpha(on = false, dimmable = true, pct = 80), 0.0001f)
        assertEquals(0.05f, washAlpha(on = true, dimmable = true, pct = 0), 0.0001f)
        assertEquals(0.16f, washAlpha(on = true, dimmable = true, pct = 100), 0.0001f)
        assertEquals(0.09f, washAlpha(on = true, dimmable = false, pct = 0), 0.0001f)
    }

    // dimCommit

    @Test
    fun `releasing at the floor turns the light off, not brightness zero`() {
        assertEquals("turn_off" to emptyMap(), dimCommit(0))
    }

    @Test
    fun `commits clamp into HA's 1-100 range`() {
        assertEquals("turn_on" to mapOf<String, Any?>("brightness_pct" to 62), dimCommit(62))
        assertEquals("turn_on" to mapOf<String, Any?>("brightness_pct" to 100), dimCommit(140))
    }
}
