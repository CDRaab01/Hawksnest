package com.hawksnest.ui.cameras

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Go2rtcTest {

    @Test
    fun `builds a ws url from an http origin, url-encoding the stream name`() {
        assertEquals(
            "ws://ha.local:8080/go2rtc/api/ws?src=front_door",
            go2rtcWsUrl("http://ha.local:8080", "front_door"),
        )
    }

    @Test
    fun `builds a wss url from an https origin and trims a trailing slash`() {
        assertEquals(
            "wss://dragonfly.tail2ce561.ts.net:8443/go2rtc/api/ws?src=front+door",
            go2rtcWsUrl("https://dragonfly.tail2ce561.ts.net:8443/", "front door"),
        )
    }

    @Test
    fun `media circuit-breaker skips once broken, clears on success`() {
        Go2rtcHealth.report(true)
        assertTrue(Go2rtcHealth.maybeAvailable())
        Go2rtcHealth.report(false)
        assertFalse(Go2rtcHealth.maybeAvailable()) // one failure disables the tier
        Go2rtcHealth.report(true)
        assertTrue(Go2rtcHealth.maybeAvailable())
    }
}
