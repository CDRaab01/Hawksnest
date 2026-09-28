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
        return LogicalCamera(
            id = id,
            name = name,
            liveEntity = e,
            snapshotEntity = e,
            eventStreamId = null,
            eventSelectId = null,
            dingId = dingId,
            motionId = null,
        )
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

    /** A ding as it arrives over the compressed websocket: last_changed in epoch SECONDS. */
    private fun wsDing(id: String, state: String, whenMs: Long): HassEntity = HassEntity(
        entityId = id,
        state = state,
        attributes = JsonObject(emptyMap()),
        // Plain decimal like HA's own frames ("1727460000.123"), not Double.toString's "1.7E9".
        lastChanged = java.math.BigDecimal.valueOf(whenMs).movePointLeft(3).toPlainString(),
    )

    @Test
    fun `reads a websocket press time in epoch seconds`() {
        // Every live update arrives in this shape. The ISO-only parser failed on it and fell back
        // to "now", so each press looked brand new: the 30 s window never closed and the banner's
        // dismiss was undone by the next push.
        val cameras = listOf(cam("camera.front_door_reolink", "Front Door", "binary_sensor.front_door_reolink_visitor"))
        val entities = mapOf(
            "binary_sensor.front_door_reolink_visitor" to
                wsDing("binary_sensor.front_door_reolink_visitor", "on", NOW - 5_000),
        )
        assertEquals(NOW - 5_000, activeDoorbellPress(cameras, entities, NOW)?.whenMs)
    }

    @Test
    fun `a websocket press older than the window has expired`() {
        val cameras = listOf(cam("camera.front", "Front", "binary_sensor.front_ding"))
        val entities = mapOf(
            "binary_sensor.front_ding" to wsDing("binary_sensor.front_ding", "on", NOW - 60_000),
        )
        assertNull(activeDoorbellPress(cameras, entities, NOW, 30_000))
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
