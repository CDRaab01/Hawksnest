package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builders + parse helpers for the Home Assistant WebSocket envelope. Frames are parsed as raw
 * [JsonObject] and inspected per-call (the payloads are domain-specific), mirroring how the web
 * `home-assistant-js-websocket` lib + `haSource.ts` use untyped messages. Pure + unit-testable.
 */
object HaMessages {
    const val TYPE_AUTH_REQUIRED = "auth_required"
    const val TYPE_AUTH = "auth"
    const val TYPE_AUTH_OK = "auth_ok"
    const val TYPE_AUTH_INVALID = "auth_invalid"
    const val TYPE_RESULT = "result"
    const val TYPE_EVENT = "event"
    const val TYPE_PONG = "pong"

    /** The auth-handshake reply: `{type:"auth", access_token:"…"}` (no id — pre-auth). */
    fun auth(token: String): JsonObject = buildJsonObject {
        put("type", TYPE_AUTH)
        put("access_token", token)
    }

    /** A generic post-auth command: `{id, type, …}`. */
    fun command(id: Int, type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", type)
            build()
        }

    /** `call_service` with an optional entity target and extra service data (brightness, temp, …). */
    fun callService(
        id: Int,
        domain: String,
        service: String,
        entityId: String?,
        serviceData: Map<String, Any?> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "call_service")
        put("domain", domain)
        put("service", service)
        if (serviceData.isNotEmpty()) {
            putJsonObject("service_data") {
                serviceData.forEach { (k, v) -> put(k, anyToJsonElement(v)) }
            }
        }
        if (entityId != null) putJsonObject("target") { put("entity_id", entityId) }
    }

    /** The `type` field of any incoming frame, or null. */
    fun frameType(frame: JsonObject): String? = frame["type"]?.jsonPrimitive?.contentOrNull

    /** The `id` field of a result/event frame, or null. */
    fun frameId(frame: JsonObject): Int? = frame["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** Whether a `result` frame succeeded. */
    fun resultSuccess(frame: JsonObject): Boolean =
        frame["success"]?.jsonPrimitive?.booleanOrNull == true

    /** Human error message from an `auth_invalid` / failed `result` frame. */
    fun errorMessage(frame: JsonObject): String? =
        frame["message"]?.jsonPrimitive?.contentOrNull
            ?: (frame["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
}

/** Convert a loosely-typed service-data value into a JSON element for the WS payload. */
internal fun anyToJsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}
