package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `recordedBackend.ts` + `frigate.ts` behavior (see the web `__tests__` twins). */
class RecordedBackendTest {

    private fun frigateEntity(vararg attrs: Pair<String, String>) = HassEntity(
        entityId = "camera.big_room",
        state = "idle",
        attributes = buildJsonObject { attrs.forEach { (k, v) -> put(k, v) } },
    )

    @Test
    fun `ring selector wins over frigate`() {
        assertEquals(RecordedBackend.RING, recordedBackendOf(hasRingSelector = true, hasFrigateCamera = true))
    }

    // camera.front, verbatim: a Frigate camera whose base name still carries the retired Ring
    // camera's `unavailable` selector. Ring cannot answer, so it must not win.
    @Test
    fun `a registered but dead ring selector loses to frigate`() {
        assertEquals(
            RecordedBackend.FRIGATE,
            recordedBackendOf(hasRingSelector = true, hasFrigateCamera = true, ringSelectorLive = false),
        )
    }

    // A ring-mqtt restart must not cost a ring-only camera its timeline for the whole session:
    // a dead selector loses only to a backend that can actually answer.
    @Test
    fun `a dead ring selector still wins when nothing else records`() {
        assertEquals(
            RecordedBackend.RING,
            recordedBackendOf(hasRingSelector = true, hasFrigateCamera = false, ringSelectorLive = false),
        )
    }

    @Test
    fun `frigate when only frigate knows the camera`() {
        assertEquals(RecordedBackend.FRIGATE, recordedBackendOf(hasRingSelector = false, hasFrigateCamera = true))
    }

    @Test
    fun `none when nobody records`() {
        assertEquals(RecordedBackend.NONE, recordedBackendOf(hasRingSelector = false, hasFrigateCamera = false))
    }

    @Test
    fun `real recordings for ring and frigate, not none`() {
        assertTrue(hasRealRecordings(RecordedBackend.RING))
        assertTrue(hasRealRecordings(RecordedBackend.FRIGATE))
        assertFalse(hasRealRecordings(RecordedBackend.NONE))
    }

    @Test
    fun `isFrigateCamera requires both stamped attributes`() {
        assertTrue(isFrigateCamera(frigateEntity("client_id" to "frigate", "camera_name" to "big_room")))
        assertFalse(isFrigateCamera(frigateEntity("client_id" to "frigate")))
        assertFalse(isFrigateCamera(frigateEntity("camera_name" to "big_room")))
        assertFalse(isFrigateCamera(frigateEntity()))
        assertFalse(isFrigateCamera(null))
    }

    @Test
    fun `frigateCameraName reads the stamped name`() {
        assertEquals(
            "big_room",
            frigateCameraName(frigateEntity("client_id" to "frigate", "camera_name" to "big_room")),
        )
        assertNull(frigateCameraName(frigateEntity("client_id" to "frigate", "camera_name" to "")))
        assertNull(frigateCameraName(null))
    }

    @Test
    fun `retention comes from the sensor with the constant as fallback`() {
        fun sensor(state: String) = HassEntity(
            entityId = FRIGATE_RETENTION_SENSOR,
            state = state,
            attributes = buildJsonObject {},
        )
        assertEquals(7.0, frigateRetentionDays(sensor("7"), fallback = 3.0))
        assertEquals(3.0, frigateRetentionDays(sensor("3.0"), fallback = 99.0))
        assertEquals(3.0, frigateRetentionDays(null, fallback = 3.0))
        assertEquals(3.0, frigateRetentionDays(sensor("unavailable"), fallback = 3.0))
        // Zero/negative would collapse the timeline — treat as unreadable, not as a choice.
        assertEquals(3.0, frigateRetentionDays(sensor("0"), fallback = 3.0))
    }

    @Test
    fun `non-string stamps do not match`() {
        val entity = HassEntity(
            entityId = "camera.big_room",
            state = "idle",
            attributes = buildJsonObject {
                put("client_id", 5)
                put("camera_name", "big_room")
            },
        )
        assertFalse(isFrigateCamera(entity))
    }
}
