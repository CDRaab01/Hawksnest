package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `cameraUrl.test.ts` 1:1. */
class CameraUrlTest {

    private fun cam(
        state: String = "idle",
        picture: String? = "/api/camera_proxy/camera.front_door?token=abc123",
    ): HassEntity {
        val attrs: JsonObject = buildJsonObject { if (picture != null) put("entity_picture", picture) }
        return HassEntity(entityId = "camera.front_door", state = state, attributes = attrs)
    }

    @Test
    fun `reads the signed snapshot URL off entity_picture`() {
        assertEquals("/api/camera_proxy/camera.front_door?token=abc123", snapshotUrl(cam()))
    }

    @Test
    fun `returns null without entity_picture`() {
        assertNull(snapshotUrl(cam(picture = null)))
        assertNull(streamUrl(cam(picture = null)))
    }

    @Test
    fun `appends a cache-buster with the right separator`() {
        assertEquals("/api/camera_proxy/camera.front_door?token=abc123&_=42", snapshotUrlAt(cam(), 42))
        assertEquals(
            "/api/camera_proxy/camera.x?_=7",
            snapshotUrlAt(cam(picture = "/api/camera_proxy/camera.x"), 7),
        )
    }

    @Test
    fun `derives the MJPEG stream URL reusing the token`() {
        assertEquals("/api/camera_proxy_stream/camera.front_door?token=abc123", streamUrl(cam()))
    }

    @Test
    fun `resolves snapshot and stream against the connected HA origin`() {
        val base = "http://192.168.4.34:8123"
        assertEquals(
            "http://192.168.4.34:8123/api/camera_proxy/camera.front_door?token=abc123",
            snapshotUrl(cam(), base),
        )
        assertEquals(
            "http://192.168.4.34:8123/api/camera_proxy_stream/camera.front_door?token=abc123",
            streamUrl(cam(), base),
        )
    }

    @Test
    fun `trims a trailing slash and leaves absolute pictures alone`() {
        assertEquals(
            "http://ha.local:8123/api/camera_proxy/camera.front_door?token=abc123",
            snapshotUrl(cam(), "http://ha.local:8123/"),
        )
        val absolute = cam(picture = "https://nabu.example/api/camera_proxy/camera.x?token=z")
        assertEquals("https://nabu.example/api/camera_proxy/camera.x?token=z", snapshotUrl(absolute, "http://ha.local:8123"))
    }

    @Test
    fun `gates availability on a signed URL and a live state`() {
        assertTrue(isCameraLive(cam()))
        assertFalse(isCameraLive(cam(state = "unavailable")))
        assertFalse(isCameraLive(cam(picture = null)))
    }
}
