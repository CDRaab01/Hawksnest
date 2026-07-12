package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DedupeTest {

    private fun light(id: String, name: String) = HassEntity(
        entityId = id,
        state = "off",
        attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name))),
    )

    @Test
    fun `a ring twin of an mqtt light is dropped — the mqtt one survives`() {
        val entities = mapOf(
            "light.front_light" to light("light.front_light", "Front Light"),
            "light.front_light_2" to light("light.front_light_2", "Front Light"),
        )
        val platforms = mapOf(
            "light.front_light" to MQTT_PLATFORM,
            "light.front_light_2" to RING_PLATFORM,
        )
        val out = dedupeRingMqtt(entities, platforms)
        assertEquals(setOf("light.front_light"), out.keys)
    }

    @Test
    fun `a ring entity with no mqtt twin survives`() {
        val entities = mapOf("light.porch" to light("light.porch", "Porch"))
        val out = dedupeRingMqtt(entities, mapOf("light.porch" to RING_PLATFORM))
        assertEquals(setOf("light.porch"), out.keys)
    }

    @Test
    fun `different names or domains never collide`() {
        val entities = mapOf(
            "light.front" to light("light.front", "Front Light"),
            "light.back" to light("light.back", "Back Light"),
            "switch.front_light" to HassEntity(
                entityId = "switch.front_light",
                state = "off",
                attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive("Front Light"))),
            ),
        )
        val platforms = mapOf(
            "light.front" to MQTT_PLATFORM,
            "light.back" to RING_PLATFORM,
            "switch.front_light" to RING_PLATFORM,
        )
        assertEquals(entities.keys, dedupeRingMqtt(entities, platforms).keys)
    }

    @Test
    fun `non-ring platforms are untouched even on a name collision`() {
        val entities = mapOf(
            "light.hall" to light("light.hall", "Hall"),
            "light.hall_z" to light("light.hall_z", "Hall"),
        )
        val platforms = mapOf(
            "light.hall" to MQTT_PLATFORM,
            "light.hall_z" to "zwave_js",
        )
        assertEquals(entities.keys, dedupeRingMqtt(entities, platforms).keys)
    }

    @Test
    fun `empty platform map (registry not loaded yet) passes everything through`() {
        val entities = mapOf("light.front" to light("light.front", "Front Light"))
        assertEquals(entities.keys, dedupeRingMqtt(entities, emptyMap()).keys)
    }
}
