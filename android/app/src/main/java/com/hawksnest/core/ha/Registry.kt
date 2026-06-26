package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolve each entity's area name from HA's three registry list responses
 * (`config/{area,entity,device}_registry/list`). An entity's area comes from its own `area_id`,
 * falling back to its device's `area_id`. Entities with no resolvable area are omitted (they land
 * in "Unassigned" at group time). Ported from `src/store/ha/registry.ts buildAreaRegistry`.
 */
fun buildAreaRegistry(
    areas: JsonArray,
    entities: JsonArray,
    devices: JsonArray,
): AreaRegistry {
    val nameByArea: Map<String, String> = areas.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val areaId = o.str("area_id") ?: return@mapNotNull null
        val name = o.str("name") ?: return@mapNotNull null
        areaId to name
    }.toMap()

    val areaByDevice: Map<String, String?> = devices.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        id to o.str("area_id")
    }.toMap()

    val out = LinkedHashMap<String, String>()
    for (el in entities) {
        val o = el as? JsonObject ?: continue
        val entityId = o.str("entity_id") ?: continue
        val areaId = o.str("area_id") ?: o.str("device_id")?.let { areaByDevice[it] } ?: continue
        val name = nameByArea[areaId] ?: continue
        out[entityId] = name
    }
    return out
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
