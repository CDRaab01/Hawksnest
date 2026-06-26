package com.hawksnest.core.logic

/**
 * The Tailscale "sidecar" connection model. Hawksnest is **not** a VPN — it reaches Home Assistant
 * over the tunnel the official Tailscale app already provides on the device. These pure helpers let
 * Settings recognise the official app and nudge the user toward a tailnet base URL; the actual
 * VPN/auth lives entirely in the Tailscale app.
 */
object Tailscale {
    /** The official Tailscale Android app package — opened/installed from Settings. */
    const val PACKAGE = "com.tailscale.ipn"

    /** Play Store + web fallbacks for installing the Tailscale app. */
    const val MARKET_URI = "market://details?id=$PACKAGE"
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** Bare host of [url] (no scheme, userinfo, port, or path). Null when none can be parsed. */
    fun host(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val afterScheme = if ("://" in trimmed) trimmed.substringAfter("://") else trimmed
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val hostPort = authority.substringAfterLast('@') // drop any user:pass@
        val host = hostPort.substringBefore(':')          // drop :port (IPv6 not handled — uncommon here)
        return host.ifBlank { null }
    }

    /**
     * True when [url]'s host looks like it lives on a tailnet: a MagicDNS `*.ts.net` name, or a
     * 100.64.0.0/10 CGNAT address (the range Tailscale hands out to nodes). Used only as a UX hint —
     * a non-tailnet host (a LAN IP, a public DNS name) still connects fine when reachable.
     */
    fun isTailnetHost(url: String): Boolean {
        val h = host(url)?.lowercase() ?: return false
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() }
            if (octets.all { it != null && it in 0..255 }) {
                // 100.64.0.0/10 → first octet 100, second octet 64..127
                if (octets[0] == 100 && octets[1]!! in 64..127) return true
            }
        }
        return false
    }
}
