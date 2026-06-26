package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies one HA `subscribe_entities` event to the current entity map, implementing HA's compressed
 * format. Pure (no socket) so it can be unit-tested against captured frames.
 *
 * Event keys:
 *  - `a` (add): `{ entity_id: { s: state, a: {attrs}, lc: float, lu: float, c: ctx } }` — full state.
 *  - `c` (change): `{ entity_id: { "+": { s?, a?: {changed}, lu? }, "-": { a: [removed_keys] } } }`.
 *  - `r` (remove): `[ entity_id, … ]`.
 *
 * Mirrors what `subscribeEntities` decodes for the web app (`src/store/haSource.ts`).
 */
fun applyEntitiesEvent(
    current: Map<String, HassEntity>,
    event: JsonObject,
): Map<String, HassEntity> {
    val next = current.toMutableMap()

    (event["a"] as? JsonObject)?.forEach { (id, raw) ->
        val obj = raw as? JsonObject ?: return@forEach
        next[id] = obj.toEntity(id)
    }

    (event["c"] as? JsonObject)?.forEach { (id, raw) ->
        val change = raw as? JsonObject ?: return@forEach
        val existing = next[id] ?: HassEntity(entityId = id, state = "")
        next[id] = existing.applyChange(change)
    }

    (event["r"] as? JsonArray)?.forEach { idEl ->
        idEl.jsonPrimitive.contentOrNull?.let { next.remove(it) }
    }

    return next
}

/** Build a full entity from a compressed add (`{s,a,lc,lu}`). */
private fun JsonObject.toEntity(id: String): HassEntity = HassEntity(
    entityId = id,
    state = this["s"]?.jsonPrimitive?.contentOrNull ?: "",
    attributes = (this["a"] as? JsonObject) ?: JsonObject(emptyMap()),
    lastChanged = numberAsString(this["lc"]) ?: numberAsString(this["lu"]),
    lastUpdated = numberAsString(this["lu"]) ?: numberAsString(this["lc"]),
)

/** Apply a compressed change delta (`{"+":…, "-":…}`) to an existing entity. */
private fun HassEntity.applyChange(change: JsonObject): HassEntity {
    var state = state
    val attrs = attributes.toMutableMap()
    var lastUpdated = lastUpdated
    var lastChanged = lastChanged

    (change["+"] as? JsonObject)?.let { plus ->
        plus["s"]?.jsonPrimitive?.contentOrNull?.let { state = it }
        (plus["a"] as? JsonObject)?.forEach { (k, v) -> attrs[k] = v }
        numberAsString(plus["lu"])?.let { lastUpdated = it }
        numberAsString(plus["lc"])?.let { lastChanged = it }
    }
    (change["-"] as? JsonObject)?.let { minus ->
        (minus["a"] as? JsonArray)?.forEach { keyEl ->
            keyEl.jsonPrimitive.contentOrNull?.let { attrs.remove(it) }
        }
    }

    return HassEntity(
        entityId = entityId,
        state = state,
        attributes = JsonObject(attrs),
        lastChanged = lastChanged,
        lastUpdated = lastUpdated,
    )
}

/** HA sends `lc`/`lu` as epoch-second floats; keep them as their raw string (display-only). */
private fun numberAsString(el: kotlinx.serialization.json.JsonElement?): String? =
    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
