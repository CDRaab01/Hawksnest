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

    @Test
    fun `entity categories keeps only config and diagnostic`() {
        val entities = arr(
            """[
              {"entity_id":"camera.basement","entity_category":null},
              {"entity_id":"sensor.basement_battery","entity_category":"diagnostic"},
              {"entity_id":"number.basement_volume","entity_category":"config"},
              {"entity_id":"switch.basement_siren"}
            ]""",
        )
        val cats = buildEntityCategories(entities)
        assertEquals("diagnostic", cats["sensor.basement_battery"])
        assertEquals("config", cats["number.basement_volume"])
        assertNull(cats["camera.basement"]) // primary entity -> not hidden
        assertNull(cats["switch.basement_siren"]) // no category -> not hidden
    }

    @Test
    fun `zwave entity ids keeps only the zwave_js platform`() {
        val entities = arr(
            """[
              {"entity_id":"lock.front","platform":"zwave_js"},
              {"entity_id":"light.basement","platform":"zwave_js"},
              {"entity_id":"camera.porch","platform":"ring"},
              {"entity_id":"sensor.weather"}
            ]""",
        )
        assertEquals(listOf("lock.front", "light.basement"), buildZWaveEntityIds(entities))
    }

    @Test
    fun `device index maps device to its entities and back`() {
        val areas = arr("""[{"area_id":"a1","name":"Basement"}]""")
        val devices = arr("""[{"id":"d1","area_id":"a1","name":"Basement Cam","name_by_user":"Basement"}]""")
        val entities = arr(
            """[
              {"entity_id":"camera.basement","device_id":"d1"},
              {"entity_id":"sensor.basement_battery","device_id":"d1"},
              {"entity_id":"light.lr","device_id":null}
            ]""",
        )
        val index = buildDeviceIndex(areas, entities, devices)
        assertEquals("Basement", index.devices["d1"]?.name) // name_by_user wins
        assertEquals("Basement", index.devices["d1"]?.area)
        assertEquals(
            listOf("camera.basement", "sensor.basement_battery"),
            index.devices["d1"]?.entityIds,
        )
        assertEquals("d1", index.deviceByEntity["sensor.basement_battery"])
        assertNull(index.deviceByEntity["light.lr"]) // no device -> absent
    }
}
