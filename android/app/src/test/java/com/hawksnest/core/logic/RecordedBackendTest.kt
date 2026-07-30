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
