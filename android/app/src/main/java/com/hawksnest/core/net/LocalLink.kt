package com.hawksnest.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hawksnest.core.logic.LinkFacts
import com.hawksnest.core.logic.hasLocalLink

/**
 * Reads the phone's networks into [LinkFacts] and asks [hasLocalLink] — see core/logic/LiveLink.kt
 * for the why. Synchronous and cheap (no I/O), so the camera player reads it once per open.
 * Anything unreadable answers false (remote), the safe direction.
 */
@Suppress("DEPRECATION") // allNetworks: the one API that lists the networks UNDER the VPN.
fun isOnLocalLink(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val links = cm.allNetworks.mapNotNull { net ->
        cm.getNetworkCapabilities(net)?.let { caps ->
            LinkFacts(
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
    }
    hasLocalLink(links)
}.getOrDefault(false)
