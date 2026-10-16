package com.hawksnest.core.logic

/**
 * What kind of link the phone is on, for choosing how a camera opens. The vendor app's rule, and
 * now ours: on unmetered Wi-Fi open the full-resolution stream straight off the camera; anywhere
 * else open the light one.
 *
 * Why it matters (2026-10-15): the owner's wife could not get any camera to play away from home.
 * The ladder's first rung, RTSP-direct, plays the camera's MAIN stream — fixed ~5 Mbps, no
 * adaptation — and off the LAN that runs cellular → tailnet → the Dragonfly host → the camera over
 * TCP. A link that cannot carry it does not degrade, it stalls; the 7 s stall timeout stepped
 * down to the relay, which then played the same main stream, and she reopened. Her proxy log was
 * reopen loops; the owner, on home Wi-Fi, never saw it. The Reolink app opens "Fluent" (sub)
 * whenever it is remote for exactly this reason.
 */
data class LinkFacts(
    /** A VPN (Tailscale) — never the answer itself, only a wrapper around the real link. */
    val vpn: Boolean,
    val wifiOrEthernet: Boolean,
    val notMetered: Boolean,
    /** Android confirmed this link reaches the internet; an unvalidated Wi-Fi is a captive portal
     *  or a dead router, not a link to stream over. */
    val validated: Boolean,
)

/**
 * Whether the phone has a link that can carry a camera's main stream: some real (non-VPN) network
 * that is Wi-Fi or Ethernet, unmetered, and validated.
 *
 * Scans every network rather than reading the default one because the default is Tailscale's VPN,
 * whose own capabilities depend on what the VPN app declares. A phone on home Wi-Fi with mobile
 * data also up (Samsung keeps it up) routes over the Wi-Fi, so "a good Wi-Fi exists" is the right
 * question. A phone tethered to another phone's hotspot is metered, so it reads as remote — which
 * is what it is, bandwidth-wise. Unknown (no networks readable) answers false: the cost of a wrong
 * "remote" is a lighter first picture, one tap from High; the cost of a wrong "local" is the stall.
 */
fun hasLocalLink(links: List<LinkFacts>): Boolean {
    fun good(it: LinkFacts) = it.wifiOrEthernet && it.notMetered && it.validated
    val real = links.filter { !it.vpn }
    // If the platform shows only the VPN, fall back to its capabilities, which Android merges from
    // the network underneath (transports and metered-ness) — better than calling home Wi-Fi remote.
    return if (real.isNotEmpty()) real.any(::good) else links.any(::good)
}

/**
 * Whether this open plays the light stream (go2rtc's `<name>_sub`).
 *
 * - [rememberedLow]: the user chose Low for this camera before (persisted; wins everywhere).
 * - [localLink]: see [hasLocalLink]. Off it, Low is the default.
 * - [highChosen]: the user tapped High during THIS open. Not persisted — away from home the next
 *   open starts light again, and at home the remembered choice is what it always was.
 */
fun liveQualityLow(rememberedLow: Boolean, localLink: Boolean, highChosen: Boolean): Boolean =
    rememberedLow || (!localLink && !highChosen)

/**
 * Whether RTSP-direct may be tried at all. Only on a local link: away from home it is the same
 * fixed-bitrate main stream as the relay's High, but over TCP through the host, where a weak link
 * stalls for the full timeout before stepping down — the relay's WebRTC copes with loss instead.
 */
fun directAllowed(localLink: Boolean): Boolean = localLink
