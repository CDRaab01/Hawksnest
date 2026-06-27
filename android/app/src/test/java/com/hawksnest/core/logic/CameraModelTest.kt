package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ports `cameraModel.test.ts` 1:1. */
class CameraModelTest {

    private fun ent(id: String, friendly: String? = null): HassEntity = HassEntity(
        entityId = id,
        state = "idle",
        attributes = buildJsonObject { if (friendly != null) put("friendly_name", friendly) },
    )

    private fun map(vararg es: HassEntity): Map<String, HassEntity> = es.associateBy { it.entityId }

    @Test
    fun `collapses a ring-mqtt camera's entities into one logical camera`() {
        val cams = resolveCameras(
            map(
                ent("camera.front_door_live", "Front Door Live"),
                ent("camera.front_door_snapshot", "Front Door Snapshot"),
                ent("camera.front_door_event", "Front Door Event"),
                ent("select.front_door_event_select", "Front Door Event Select"),
                ent("binary_sensor.front_door_ding", "Front Door Ding"),
                ent("binary_sensor.front_door_motion", "Front Door Motion"),
                ent("switch.front_door_siren", "Front Door Siren"),
                ent("light.kitchen", "Kitchen"),
            ),
        )
        assertEquals(1, cams.size)
        val c = cams[0]
        assertEquals("camera.front_door", c.id)
        assertEquals("Front Door", c.name)
        assertEquals("camera.front_door_live", c.liveEntity.entityId)
        assertEquals("camera.front_door_snapshot", c.snapshotEntity.entityId)
        assertEquals("camera.front_door_event", c.eventStreamId)
        assertEquals("select.front_door_event_select", c.eventSelectId)
        assertEquals("binary_sensor.front_door_ding", c.dingId)
        assertEquals("binary_sensor.front_door_motion", c.motionId)
        assertEquals("switch.front_door_siren", c.sirenSwitchId)
    }

    @Test
    fun `maps a plain HA camera to a logical camera with no siblings`() {
        val cams = resolveCameras(map(ent("camera.driveway", "Driveway")))
        assertEquals(1, cams.size)
        val c = cams[0]
        assertEquals("camera.driveway", c.id)
        assertEquals("Driveway", c.name)
        assertEquals(null, c.eventSelectId)
        assertEquals(null, c.dingId)
        assertEquals(null, c.sirenSwitchId)
        assertEquals("camera.driveway", c.liveEntity.entityId)
        assertEquals("camera.driveway", c.snapshotEntity.entityId)
    }

    @Test
    fun `folds HA Ring's _live_view entity into the base camera`() {
        val cams = resolveCameras(
            map(
                ent("camera.back", "Back"),
                ent("camera.back_live_view", "Back Live view"),
            ),
        )
        assertEquals(1, cams.size)
        val c = cams[0]
        assertEquals("camera.back", c.id)
        assertEquals("Back", c.name)
        assertEquals("camera.back_live_view", c.liveEntity.entityId)
        assertEquals("camera.back", c.snapshotEntity.entityId)
    }

    @Test
    fun `handles a live-only ring camera and sorts by id`() {
        val cams = resolveCameras(
            map(ent("camera.zzz_live", "Zzz Live"), ent("camera.aaa", "Aaa")),
        )
        assertEquals(listOf("camera.aaa", "camera.zzz"), cams.map { it.id })
        assertEquals("camera.zzz_live", cams.first { it.id == "camera.zzz" }.snapshotEntity.entityId)
    }
}
