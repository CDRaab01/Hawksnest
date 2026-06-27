package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ports `entityVisibility.test.ts` 1:1. */
class EntityVisibilityTest {

    private val categories = mapOf(
        "sensor.back_battery" to "diagnostic",
        "number.back_volume" to "config",
    )

    private fun ent(id: String): HassEntity =
        HassEntity(entityId = id, state = "on", attributes = buildJsonObject {})

    @Test
    fun `keeps real controls and signals`() {
        for (id in listOf(
            "lock.front_door",
            "light.basement",
            "camera.back",
            "binary_sensor.back_motion",
            "switch.back_siren",
            "alarm_control_panel.mfa_alarm",
        )) {
            assertTrue(isPrimaryEntity(id, categories), id)
        }
    }

    @Test
    fun `drops HA config and diagnostic entities`() {
        assertFalse(isPrimaryEntity("sensor.back_battery", categories))
        assertFalse(isPrimaryEntity("number.back_volume", categories))
    }

    @Test
    fun `drops untagged ring-mqtt housekeeping entities by suffix`() {
        for (id in listOf(
            "sensor.back_last_activity",
            "sensor.back_info",
            "camera.back_event_stream",
            "camera.back_live_stream",
            "select.back_event_select",
            "select.back_bypass_mode",
            "select.back_chirp_tone",
            "camera.back_snapshot",
            "camera.back_live_view",
        )) {
            assertTrue(isNoiseEntity(id), id)
            assertFalse(isPrimaryEntity(id, emptyMap()), id)
        }
    }

    @Test
    fun `does not treat a real control as noise`() {
        assertFalse(isNoiseEntity("lock.front_door"))
        assertFalse(isNoiseEntity("binary_sensor.back_motion"))
    }

    @Test
    fun `primaryEntities filters a list`() {
        val list = listOf(
            ent("lock.front_door"),
            ent("sensor.back_last_activity"),
            ent("sensor.back_battery"),
            ent("light.basement"),
        )
        assertEquals(
            listOf("lock.front_door", "light.basement"),
            primaryEntities(list, categories).map { it.entityId },
        )
    }
}
