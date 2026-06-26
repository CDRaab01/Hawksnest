package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ports `doorbell.test.ts`. */
class DoorbellTest {

    private val NOW = 1_700_000_000_000L

    private fun cam(id: String, name: String, dingId: String?): LogicalCamera {
        val e = HassEntity(entityId = "${id}_x", state = "idle", attributes = JsonObject(emptyMap()))
        return LogicalCamera(id, name, e, e, null, null, dingId, null)
    }

    private fun ding(id: String, state: String, whenMs: Long): HassEntity = HassEntity(
        entityId = id,
        state = state,
        attributes = JsonObject(emptyMap()),
        lastChanged = Instant.ofEpochMilli(whenMs).toString(),
    )

    @Test
    fun `returns a press when a ding sensor is on within the window`() {
        val cameras = listOf(cam("camera.front", "Front Door", "binary_sensor.front_ding"))
        val entities = mapOf("binary_sensor.front_ding" to ding("binary_sensor.front_ding", "on", NOW - 5_000))
        assertEquals(
            DoorbellPress("camera.front", "Front Door", NOW - 5_000),
            activeDoorbellPress(cameras, entities, NOW),
        )
    }

    @Test
    fun `ignores off, stale, and ding-less cameras`() {
        val cameras = listOf(
            cam("camera.front", "Front", "binary_sensor.front_ding"),
            cam("camera.yard", "Yard", null),
        )
        assertNull(
            activeDoorbellPress(
                cameras,
                mapOf("binary_sensor.front_ding" to ding("binary_sensor.front_ding", "off", NOW)),
                NOW,
            ),
        )
        assertNull(
            activeDoorbellPress(
                cameras,
                mapOf("binary_sensor.front_ding" to ding("binary_sensor.front_ding", "on", NOW - 60_000)),
                NOW,
                30_000,
            ),
        )
    }

    @Test
    fun `picks the most recent press across cameras`() {
        val cameras = listOf(
            cam("camera.a", "A", "binary_sensor.a_ding"),
            cam("camera.b", "B", "binary_sensor.b_ding"),
        )
        val entities = mapOf(
            "binary_sensor.a_ding" to ding("binary_sensor.a_ding", "on", NOW - 10_000),
            "binary_sensor.b_ding" to ding("binary_sensor.b_ding", "on", NOW - 2_000),
        )
        assertEquals("camera.b", activeDoorbellPress(cameras, entities, NOW)?.cameraId)
    }
}
