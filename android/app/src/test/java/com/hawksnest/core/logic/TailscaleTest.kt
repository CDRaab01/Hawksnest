package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleTest {

    @Test
    fun `host strips scheme, port, and path`() {
        assertEquals("hawksnest.tail123.ts.net", Tailscale.host("http://hawksnest.tail123.ts.net:8080/api"))
        assertEquals("192.168.4.34", Tailscale.host("http://192.168.4.34:8123"))
        assertEquals("ha.example.com", Tailscale.host("https://ha.example.com/"))
        assertEquals("100.101.102.103", Tailscale.host("100.101.102.103"))
    }

    @Test
    fun `host returns null for blank`() {
        assertNull(Tailscale.host(""))
        assertNull(Tailscale.host("   "))
    }

    @Test
    fun `magicdns hosts are tailnet hosts`() {
        assertTrue(Tailscale.isTailnetHost("http://hawksnest.tail123.ts.net:8080"))
        assertTrue(Tailscale.isTailnetHost("https://HA.Example.TS.NET")) // case-insensitive
    }

    @Test
    fun `cgnat addresses are tailnet hosts`() {
        assertTrue(Tailscale.isTailnetHost("http://100.64.0.1:8080"))
        assertTrue(Tailscale.isTailnetHost("http://100.127.255.255"))
        assertTrue(Tailscale.isTailnetHost("100.101.102.103"))
    }

    @Test
    fun `lan and public hosts are not tailnet hosts`() {
        assertFalse(Tailscale.isTailnetHost("http://192.168.4.34:8123")) // LAN
        assertFalse(Tailscale.isTailnetHost("http://100.200.0.1"))       // 100.x but outside CGNAT
        assertFalse(Tailscale.isTailnetHost("https://ha.example.com"))   // public DNS
        assertFalse(Tailscale.isTailnetHost(""))
    }
}
