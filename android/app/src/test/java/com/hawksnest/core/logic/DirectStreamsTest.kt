package com.hawksnest.core.logic

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectStreamsTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // The shape deploy/render-direct-streams.sh writes. Fake values only.
    private fun body(user: String = "viewer", pass: String = "p@ss\"w\\rd:/x y", cameras: String) =
        """{"version":1,"userB64":"${b64(user)}","passB64":"${b64(pass)}","cameras":{$cameras}}"""

    @Test
    fun `the server's settings parse, any password character intact`() {
        val parsed = parseDirectStreams(body(cameras = """"big_room":"10.0.0.11","front_door_reolink":"10.0.0.12""""))!!
        assertEquals("viewer", parsed.user)
        assertEquals("p@ss\"w\\rd:/x y", parsed.pass)
        assertEquals(mapOf("big_room" to "10.0.0.11", "front_door_reolink" to "10.0.0.12"), parsed.cameras)
    }

    @Test
    fun `addresses that are not plain IPv4 are dropped, and nothing usable is null`() {
        val parsed = parseDirectStreams(body(cameras = """"big_room":"10.0.0.11","garage":"cam.local","x":"999.1.1.1""""))!!
        assertEquals(setOf("big_room"), parsed.cameras.keys)
        assertNull(parseDirectStreams(body(cameras = """"garage":"cam.local"""")))
        assertNull(parseDirectStreams(body(user = "", cameras = """"big_room":"10.0.0.11"""")))
    }

    @Test
    fun `malformed bodies are treated as not provided`() {
        assertNull(parseDirectStreams(""))
        assertNull(parseDirectStreams("<html>404</html>"))
        assertNull(parseDirectStreams("""{"userB64":"!!notbase64","passB64":"eA==","cameras":{"a":"10.0.0.1"}}"""))
        assertNull(parseDirectStreams("""{"cameras":{"a":"10.0.0.1"}}"""))
    }

    @Test
    fun `a device's own complete settings win, otherwise the server's`() {
        val manual = DirectStreams("me", "mine", mapOf("kitchen" to "10.0.0.5"))
        val server = DirectStreams("viewer", "theirs", mapOf("kitchen" to "10.0.0.5", "garage" to "10.0.0.6"))
        assertEquals(manual, chooseDirectStreams(manual, server))
        assertEquals(server, chooseDirectStreams(manual.copy(pass = ""), server))
        assertEquals(server, chooseDirectStreams(null, server))
        assertNull(chooseDirectStreams(null, null))
    }

    @Test
    fun `the password never reaches toString`() {
        val s = DirectStreams("viewer", "hunter2", mapOf("kitchen" to "10.0.0.5")).toString()
        assertFalse("hunter2" in s)
        assertTrue("kitchen" in s)
    }
}
