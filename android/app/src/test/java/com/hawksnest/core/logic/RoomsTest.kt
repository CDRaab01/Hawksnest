package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the pure room-card logic ([roomIconKey] + [roomHighlights]). */
class RoomsTest {

    private fun ent(id: String, state: String, deviceClass: String? = null) = HassEntity(
        entityId = id,
        state = state,
        attributes = buildJsonObject { if (deviceClass != null) put("device_class", deviceClass) },
    )

    @Test
    fun iconKey_mapsCommonRoomNames() {
        assertEquals("kitchen", roomIconKey("Kitchen"))
        assertEquals("bedroom", roomIconKey("Bedroom 2"))
        assertEquals("living", roomIconKey("Front Room"))
        assertEquals("outdoor", roomIconKey("Backyard"))
        assertEquals("basement", roomIconKey("Basement"))
        assertEquals("frontdoor", roomIconKey("Front Door"))
        assertEquals("office", roomIconKey("Office"))
        assertEquals("unassigned", roomIconKey("Unassigned"))
        assertEquals("default", roomIconKey("Hallway Nook"))
    }

    @Test
    fun highlights_surfaceUnlockedMotionLightsCamerasTemp() {
        val highlights = roomHighlights(
            listOf(
                ent("lock.front", "unlocked"),
                ent("binary_sensor.motion", "on", "motion"),
                ent("light.a", "on"),
                ent("light.b", "off"),
                ent("camera.kitchen_live", "streaming"),
                ent("camera.kitchen_snapshot", "idle"),
                ent("sensor.temp", "71.6", "temperature"),
            ),
        )
        // Priority order, deduped camera (ring split → 1), rounded temp.
        assertEquals(
            listOf(RoomStat.UNLOCKED, RoomStat.MOTION, RoomStat.LIGHTS, RoomStat.CAMERAS),
            highlights.map { it.stat },
        )
        assertEquals("1 unlocked", highlights[0].label)
        assertEquals("1 on", highlights[2].label)
        assertEquals(Channel.STRENGTH, highlights[2].channel)
        // Capped at 4 — temperature is dropped past the cap.
        assertEquals(4, highlights.size)
    }

    @Test
    fun highlights_capRespectsTemperatureWhenRoomIsQuieter() {
        val highlights = roomHighlights(
            listOf(
                ent("light.a", "on"),
                ent("sensor.temp", "68", "temperature"),
            ),
        )
        assertEquals(listOf(RoomStat.LIGHTS, RoomStat.TEMPERATURE), highlights.map { it.stat })
        assertEquals("68°", highlights[1].label)
    }

    @Test
    fun highlights_emptyWhenNothingNotable() {
        val highlights = roomHighlights(
            listOf(
                ent("lock.front", "locked"),
                ent("binary_sensor.motion", "off", "motion"),
                ent("switch.fan", "off"),
            ),
        )
        assertTrue(highlights.isEmpty())
    }
}
