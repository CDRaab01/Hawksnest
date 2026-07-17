package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * True when a light actually supports brightness levels. HA marks on/off-only
 * lights (relay/switch-type, e.g. Ring smart lighting) with
 * `supported_color_modes: ["onoff"]` — those must not get a dimmer slider or a
 * "% brightness" readout. When the registry doesn't provide color modes at all
 * (older integrations), fall back to whether a `brightness` attribute exists.
 * Note the fallback is unreliable while the light is OFF (HA drops `brightness`
 * then), which is exactly why `supported_color_modes` is consulted first.
 */
fun isDimmableLight(attributes: JsonObject): Boolean {
    val modes = (attributes["supported_color_modes"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    if (modes != null) return modes.any { it != "onoff" }
    return attributes["brightness"] is JsonPrimitive
}
