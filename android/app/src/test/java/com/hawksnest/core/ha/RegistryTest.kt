package com.hawksnest.core.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers the `buildAreaRegistry` port (entity area_id, via-device fallback, unassigned). */
class RegistryTest {

    private fun arr(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `resolves area directly, via device, and omits unassigned`() {
        val areas = arr("""[{"area_id":"a1","name":"Living Room"},{"area_id":"a2","name":"Kitchen"}]""")
        val devices = arr("""[{"id":"d1","area_id":"a2"}]""")
        val entities = arr(
            """[
              {"entity_id":"light.lr","area_id":"a1","device_id":null},
              {"entity_id":"switch.k","area_id":null,"device_id":"d1"},
              {"entity_id":"sensor.none","area_id":null,"device_id":null}
            ]""",
        )
        val reg = buildAreaRegistry(areas, entities, devices)
        assertEquals("Living Room", reg["light.lr"])
        assertEquals("Kitchen", reg["switch.k"]) // resolved via its device's area
        assertNull(reg["sensor.none"]) // no area -> omitted (lands in "Unassigned")
    }
}
