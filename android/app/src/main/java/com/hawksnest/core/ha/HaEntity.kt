package com.hawksnest.core.ha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Minimal Home Assistant entity, compatible with the `HassEntity` from
 * `home-assistant-js-websocket` (the shape the web app consumes). Attributes stay an open
 * [JsonObject] — domain cards read typed keys (brightness, device_class, entity_picture) lazily.
 *
 * Ported from `src/lib/ha.ts`.
 */
@Serializable
data class HassEntity(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    /** ISO timestamp of the last state change (when HA provides it). */
    @SerialName("last_changed") val lastChanged: String? = null,
    /** ISO timestamp of the last update, incl. attribute-only changes. */
    @SerialName("last_updated") val lastUpdated: String? = null,
)

/** The domain is the prefix before the dot, e.g. `lock.front_door` -> `lock`. */
fun domainOf(entityId: String): String = entityId.substringBefore('.')

/** Read a string attribute, or null if absent / not a string. */
fun HassEntity.stringAttr(key: String): String? =
    (attributes[key] as? JsonPrimitive)?.contentOrNull

/** HA `friendly_name` attribute, trimmed, or null. */
fun HassEntity.friendlyName(): String? = stringAttr("friendly_name")?.trim()?.ifEmpty { null }

/** HA `icon` attribute (e.g. `mdi:lock`), or null. */
fun HassEntity.iconHint(): String? = stringAttr("icon")

/**
 * Area assignment is a runtime concern in real HA — it comes from the area + entity registries
 * (WS), never from the state stream. Modeled as a separate `entity_id -> area name` map.
 */
typealias AreaRegistry = Map<String, String>
