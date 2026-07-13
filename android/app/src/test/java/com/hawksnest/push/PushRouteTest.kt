package com.hawksnest.push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushRouteTest {

    private fun msg(title: String = "x", tags: List<String> = emptyList()) =
        NtfyMessage(id = "1", title = title, body = "b", tags = tags, priority = 3, click = null)

    @Test
    fun `bell tag routes to doorbell then cameras`() {
        val kind = PushRoute.kindOf(msg(title = "Doorbell", tags = listOf("bell")))
        assertEquals(PushKind.Doorbell, kind)
        assertEquals(PushRoute.ROUTE_CAMERAS, PushRoute.routeFor(kind))
    }

    @Test
    fun `alarm tags route to alarm then home`() {
        for (tag in listOf("shield", "rotating_light")) {
            val kind = PushRoute.kindOf(msg(title = "Alarm armed away", tags = listOf(tag)))
            assertEquals(PushKind.Alarm, kind)
            assertEquals(PushRoute.ROUTE_HOME, PushRoute.routeFor(kind))
        }
    }

    @Test
    fun `title fallback classifies when tags are missing`() {
        assertEquals(PushKind.Doorbell, PushRoute.kindOf(msg(title = "Doorbell")))
        assertEquals(PushKind.Alarm, PushRoute.kindOf(msg(title = "Alarm triggered")))
    }

    @Test
    fun `unknown message is generic and opens home`() {
        val kind = PushRoute.kindOf(msg(title = "Water leak"))
        assertEquals(PushKind.Generic, kind)
        assertEquals(PushRoute.ROUTE_HOME, PushRoute.routeFor(kind))
    }
}
