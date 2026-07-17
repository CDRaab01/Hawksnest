package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsTest {

    private fun attrs(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun `onoff-only lights are not dimmable`() {
        assertFalse(isDimmableLight(attrs("supported_color_modes" to listOf("onoff"))))
    }

    @Test
    fun `brightness-capable modes are dimmable — even while OFF with no brightness attr`() {
        assertTrue(isDimmableLight(attrs("supported_color_modes" to listOf("brightness"))))
        assertTrue(isDimmableLight(attrs("supported_color_modes" to listOf("color_temp", "xy"))))
    }

    @Test
    fun `without color modes, fall back to the brightness attribute`() {
        assertTrue(isDimmableLight(attrs("brightness" to 128)))
        assertFalse(isDimmableLight(JsonObject(emptyMap())))
    }
}
