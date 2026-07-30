package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReolinkRtspTest {

    @Test
    fun `builds the main-stream url`() {
        assertEquals(
            "rtsp://frigate:secret@192.168.1.50:554/h264Preview_01_main",
            reolinkRtspUrl("192.168.1.50", "frigate", "secret"),
        )
    }

    @Test
    fun `percent-encodes credentials that would otherwise break the url`() {
        // An unencoded `@` ends the userinfo early, so the host becomes everything after the LAST
        // `@` — the request would go somewhere else entirely, or fail with a confusing DNS error.
        val url = reolinkRtspUrl("192.168.1.50", "a@b", "p@ss/w:rd")
        assertEquals("rtsp://a%40b:p%40ss%2Fw%3Ard@192.168.1.50:554/h264Preview_01_main", url)
    }

    @Test
    fun `encodes spaces as percent-20, not plus`() {
        // URLEncoder is form-encoding: a raw `+` in userinfo is a literal `+`, so a password with a
        // space would silently authenticate with the wrong string.
        assertEquals(
            "rtsp://user:two%20words@10.0.0.2:554/h264Preview_01_main",
            reolinkRtspUrl("10.0.0.2", "user", "two words"),
        )
    }

    @Test
    fun `redacts credentials for logging`() {
        val url = reolinkRtspUrl("192.168.1.50", "frigate", "secret")
        val safe = redactRtspUrl(url)
        assertFalse(safe.contains("secret"), "password must never survive redaction")
        assertFalse(safe.contains("frigate"))
        assertEquals("rtsp://<redacted>@192.168.1.50:554/h264Preview_01_main", safe)
    }

    @Test
    fun `redaction leaves a url without credentials alone`() {
        val plain = "rtsp://192.168.1.50:554/h264Preview_01_main"
        assertEquals(plain, redactRtspUrl(plain))
    }

    @Test
    fun `validates dotted-quad addresses`() {
        assertTrue(isPlausibleIpv4("192.168.4.37"))
        assertTrue(isPlausibleIpv4("10.0.0.1"))
        assertFalse(isPlausibleIpv4("192.168.4"))
        assertFalse(isPlausibleIpv4("192.168.4.256")) // out of range
        assertFalse(isPlausibleIpv4("192.168.4.a"))
        assertFalse(isPlausibleIpv4(""))
        // Hostnames are rejected: the map comes from DHCP reservations, and a typo'd name would
        // resolve to something else on the LAN rather than failing.
        assertFalse(isPlausibleIpv4("camera.local"))
    }

    @Test
    fun `camera ip map round-trips`() {
        val ips = mapOf("big_room" to "192.168.4.37", "kitchen" to "192.168.4.64")
        assertEquals(ips, decodeCameraIps(encodeCameraIps(ips)))
    }

    @Test
    fun `encoding is stable regardless of insertion order`() {
        // An unstable string would rewrite DataStore on every save and churn its history.
        val a = encodeCameraIps(mapOf("kitchen" to "10.0.0.2", "big_room" to "10.0.0.1"))
        val b = encodeCameraIps(mapOf("big_room" to "10.0.0.1", "kitchen" to "10.0.0.2"))
        assertEquals(a, b)
    }

    @Test
    fun `decoding tolerates junk rather than breaking the camera screen`() {
        // Worst case must be "the RTSP tier is off", never a crash on the camera screen.
        assertEquals(emptyMap(), decodeCameraIps(null))
        assertEquals(emptyMap(), decodeCameraIps(""))
        assertEquals(emptyMap(), decodeCameraIps("not json"))
        assertEquals(emptyMap(), decodeCameraIps("[1,2,3]"))
        // A single bad entry drops only itself.
        assertEquals(
            mapOf("big_room" to "192.168.4.37"),
            decodeCameraIps("""{"big_room":"192.168.4.37","broken":"nope"}"""),
        )
    }

    @Test
    fun `blank addresses are dropped on the way in and out`() {
        assertEquals(emptyMap(), decodeCameraIps(encodeCameraIps(mapOf("big_room" to "   "))))
    }
}
