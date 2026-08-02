package com.hawksnest.core.ha

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The base URL a user types in Settings decides the transport scheme, and a release build
 * refuses cleartext (`cleartextTrafficPermitted="false"`). So the mapping below is the
 * difference between "connects" and "fails with a generic error that names nothing useful".
 */
class WsUrlTest {

    @Test
    fun `https becomes wss`() {
        assertEquals(
            "wss://hawksnest.tail1234.ts.net:8443/api/websocket",
            wsUrl("https://hawksnest.tail1234.ts.net:8443"),
        )
    }

    @Test
    fun `explicit http is honoured as cleartext`() {
        // Not silently upgraded: someone who types http:// on a LAN dev box means it, and a
        // debug build permits cleartext to 10.0.2.2/localhost for the instrumented mock-HA.
        assertEquals("ws://10.0.2.2:8123/api/websocket", wsUrl("http://10.0.2.2:8123"))
    }

    @Test
    fun `a host with no scheme defaults to wss, not cleartext`() {
        // The regression this file exists for. The old default was `ws://`, which a release
        // build then blocks — and the Settings placeholder made a schemeless host the natural
        // thing to type.
        assertEquals(
            "wss://hawksnest.tail1234.ts.net:8443/api/websocket",
            wsUrl("hawksnest.tail1234.ts.net:8443"),
        )
    }

    @Test
    fun `a bare tailscale IP also defaults to wss`() {
        // A scoped <domain-config> cannot match a bare IP, so if this defaulted to cleartext
        // there would be no way to permit it short of re-opening cleartext globally.
        assertEquals("wss://100.64.0.1:8123/api/websocket", wsUrl("100.64.0.1:8123"))
    }

    @Test
    fun `ws and wss are passed through untouched`() {
        assertEquals("wss://host/api/websocket", wsUrl("wss://host"))
        assertEquals("ws://host/api/websocket", wsUrl("ws://host"))
    }

    @Test
    fun `trailing slashes do not double up`() {
        assertEquals("wss://host/api/websocket", wsUrl("https://host/"))
        assertEquals("wss://host/api/websocket", wsUrl("host///"))
    }
}
