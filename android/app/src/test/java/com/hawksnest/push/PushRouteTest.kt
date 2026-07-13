package com.hawksnest.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushRouteTest {

    private fun msg(
        title: String = "x",
        tags: List<String> = emptyList(),
        click: String? = null,
    ) = NtfyMessage(id = "1", title = title, body = "b", tags = tags, priority = 3, click = click, attachUrl = null)

    @Test
    fun `bell tag classifies as doorbell`() {
        assertEquals(PushKind.Doorbell, PushRoute.kindOf(msg(title = "Doorbell", tags = listOf("bell"))))
    }

    @Test
    fun `alarm tags classify as alarm`() {
        for (tag in listOf("shield", "rotating_light")) {
            assertEquals(PushKind.Alarm, PushRoute.kindOf(msg(title = "Alarm armed away", tags = listOf(tag))))
        }
    }

    @Test
    fun `title fallback classifies when tags are missing`() {
        assertEquals(PushKind.Doorbell, PushRoute.kindOf(msg(title = "Doorbell")))
        assertEquals(PushKind.Alarm, PushRoute.kindOf(msg(title = "Alarm triggered")))
    }

    @Test
    fun `unknown message is generic`() {
        assertEquals(PushKind.Generic, PushRoute.kindOf(msg(title = "Water leak")))
    }

    @Test
    fun `cameraOf pulls the logical camera id from the click url`() {
        val m = msg(click = "https://dragonfly.tail2ce561.ts.net:8443/?camera=camera.front_door")
        assertEquals("camera.front_door", PushRoute.cameraOf(m))
    }

    @Test
    fun `cameraOf handles the param mid-query and url-encoding`() {
        assertEquals(
            "camera.back_side_yard",
            PushRoute.cameraOf(msg(click = "https://h/?x=1&camera=camera.back_side_yard&y=2")),
        )
        assertEquals(
            "camera.front door",
            PushRoute.cameraOf(msg(click = "https://h/?camera=camera.front%20door")),
        )
    }

    @Test
    fun `cameraOf is null when absent`() {
        assertNull(PushRoute.cameraOf(msg(click = null)))
        assertNull(PushRoute.cameraOf(msg(click = "https://dragonfly.ts.net:8443/")))
    }
}
