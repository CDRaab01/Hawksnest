package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLinkTest {

    private val homeWifi = LinkFacts(vpn = false, wifiOrEthernet = true, notMetered = true, validated = true)
    private val cellular = LinkFacts(vpn = false, wifiOrEthernet = false, notMetered = false, validated = true)
    private val tailscale = LinkFacts(vpn = true, wifiOrEthernet = false, notMetered = false, validated = true)

    @Test
    fun `home wifi under the tailscale VPN is local`() {
        assertTrue(hasLocalLink(listOf(tailscale, homeWifi)))
    }

    @Test
    fun `wifi with mobile data also up is still local`() {
        // Samsung keeps mobile data up beside Wi-Fi; the default route is still the Wi-Fi.
        assertTrue(hasLocalLink(listOf(tailscale, cellular, homeWifi)))
    }

    @Test
    fun `cellular under the VPN is remote`() {
        // The owner's wife's case, 2026-10-15: S23+ on Verizon through Tailscale.
        assertFalse(hasLocalLink(listOf(tailscale, cellular)))
    }

    @Test
    fun `a VPN that claims wifi does not outvote the real networks`() {
        assertFalse(hasLocalLink(listOf(tailscale.copy(wifiOrEthernet = true, notMetered = true), cellular)))
    }

    @Test
    fun `only the VPN visible falls back to its merged capabilities`() {
        assertTrue(hasLocalLink(listOf(tailscale.copy(wifiOrEthernet = true, notMetered = true))))
        assertFalse(hasLocalLink(listOf(tailscale)))
    }

    @Test
    fun `a hotspot is metered wifi and reads as remote`() {
        assertFalse(hasLocalLink(listOf(homeWifi.copy(notMetered = false))))
    }

    @Test
    fun `an unvalidated wifi is not a link to stream over`() {
        assertFalse(hasLocalLink(listOf(homeWifi.copy(validated = false), cellular)))
    }

    @Test
    fun `nothing readable answers remote`() {
        assertFalse(hasLocalLink(emptyList()))
    }

    @Test
    fun `remote opens start at Low until High is tapped`() {
        assertTrue(liveQualityLow(rememberedLow = false, localLink = false, highChosen = false))
        assertFalse(liveQualityLow(rememberedLow = false, localLink = false, highChosen = true))
    }

    @Test
    fun `at home the remembered choice is what it always was`() {
        assertFalse(liveQualityLow(rememberedLow = false, localLink = true, highChosen = false))
        assertTrue(liveQualityLow(rememberedLow = true, localLink = true, highChosen = false))
    }

    @Test
    fun `a remembered Low wins everywhere`() {
        assertTrue(liveQualityLow(rememberedLow = true, localLink = false, highChosen = false))
    }

    @Test
    fun `RTSP-direct only on a local link`() {
        assertTrue(directAllowed(localLink = true))
        assertFalse(directAllowed(localLink = false))
    }

    @Test
    fun `a remote High open lands on the relay, not RTSP-direct`() {
        // canRtsp is false off a local link, so even High rides the relay's WebRTC.
        assertEquals(
            LiveTier.RELAY,
            liveTier(
                canRtsp = directAllowed(localLink = false),
                useSub = false,
                canGo2rtc = true,
                go2rtcFailed = false,
                canWebRtc = true,
                webRtcFailed = false,
                hasHlsUrl = false,
                hasMjpeg = true,
            ),
        )
    }
}
