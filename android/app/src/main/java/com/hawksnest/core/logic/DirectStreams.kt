package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * What the direct-to-camera RTSP tier needs: the fleet's camera account and each camera's LAN IP,
 * keyed by the Frigate / go2rtc camera name the player uses.
 *
 * It comes from one of two places. The Hawksnest server hands it to any device holding a valid
 * Home Assistant token (`/hawksnest/direct-streams`, rendered from the same Secret Frigate uses),
 * so a phone that is signed in needs no setup. A device can still enter its own in Settings, which
 * wins (see [chooseDirectStreams]).
 */
data class DirectStreams(
    val user: String,
    val pass: String,
    val cameras: Map<String, String>,
) {
    /** All three parts present: without any one of them the tier can't open a stream. */
    val usable: Boolean get() = user.isNotBlank() && pass.isNotBlank() && cameras.isNotEmpty()

    /** Never print the password, even by accident in a log or a crash report. */
    override fun toString(): String = "DirectStreams(user=${user.isNotBlank()}, cameras=${cameras.keys})"
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Parse the server's `{"version":1,"userB64","passB64","cameras":{name: ip}}`. The account is
 * base64 on the wire so any character survives JSON. Cameras whose address isn't a plain IPv4 are
 * dropped, as the Settings form would refuse them. Null for anything malformed or unusable, which
 * the caller treats exactly like "not provided".
 */
fun parseDirectStreams(body: String): DirectStreams? {
    val root = runCatching { lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    fun decoded(key: String): String? = (root[key] as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
    val user = decoded("userB64") ?: return null
    val pass = decoded("passB64") ?: return null
    val cameras = (root["cameras"] as? JsonObject).orEmpty()
        .mapNotNull { (name, ip) ->
            val address = (ip as? JsonPrimitive)?.contentOrNull?.trim()
            if (name.isBlank() || address == null || !isPlausibleIpv4(address)) null else name to address
        }
        .toMap()
    return DirectStreams(user, pass, cameras).takeIf { it.usable }
}

/**
 * Which settings the player uses: the device's own, when it has a complete set, else the server's.
 * Whole sets, not merged per camera: the account and the addresses belong together, and a phone
 * set up by hand should behave exactly as it did before the server could supply them.
 */
fun chooseDirectStreams(manual: DirectStreams?, server: DirectStreams?): DirectStreams? =
    manual?.takeIf { it.usable } ?: server?.takeIf { it.usable }
