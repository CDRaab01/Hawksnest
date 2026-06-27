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

/** entity_id → HA `entity_category` ("config" | "diagnostic"). Primary entities are omitted. */
typealias EntityCategories = Map<String, String>

/** Entity categories the main Devices list + History demote out of view (kept under device detail). */
val HIDDEN_ENTITY_CATEGORIES: Set<String> = setOf("config", "diagnostic")

/** A device resolved from the registries — powers the per-device diagnostics view. */
data class DeviceRecord(
    val id: String,
    val name: String,
    val area: String?,
    /** Entity ids that belong to this device, in registry order. */
    val entityIds: List<String>,
)

/** Device id → record, plus an entity → owning-device lookup. Mirrors web `buildDeviceIndex`. */
data class DeviceIndex(
    val devices: Map<String, DeviceRecord> = emptyMap(),
    val deviceByEntity: Map<String, String> = emptyMap(),
)

/**
 * entity_id → entity_category for entities HA marks `config`/`diagnostic` — the field HA's own app
 * uses to demote battery/last-activity/volume/calibration toggles out of the primary device list.
 * Only the hidden categories are retained; primary entities are absent. Parsed from the same
 * `config/entity_registry/list` payload as [buildAreaRegistry].
 */
fun buildEntityCategories(entities: JsonArray): EntityCategories {
    val out = LinkedHashMap<String, String>()
    for (el in entities) {
        val o = el as? JsonObject ?: continue
        val entityId = o.str("entity_id") ?: continue
        val cat = o.str("entity_category") ?: continue
        if (cat in HIDDEN_ENTITY_CATEGORIES) out[entityId] = cat
    }
    return out
}

/**
 * Build the device index: one [DeviceRecord] per device (resolved name + area + its entity ids) and
 * an entity → device lookup. Lets the UI group a device's entities (and tuck its diagnostics under a
 * detail view) where [buildAreaRegistry] only kept the area name. Ported from
 * `src/store/ha/registry.ts buildDeviceIndex`.
 */
fun buildDeviceIndex(areas: JsonArray, entities: JsonArray, devices: JsonArray): DeviceIndex {
    val nameByArea: Map<String, String> = areas.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val areaId = o.str("area_id") ?: return@mapNotNull null
        val name = o.str("name") ?: return@mapNotNull null
        areaId to name
    }.toMap()

    val deviceByEntity = LinkedHashMap<String, String>()
    val entityIdsByDevice = LinkedHashMap<String, MutableList<String>>()
    for (el in entities) {
        val o = el as? JsonObject ?: continue
        val entityId = o.str("entity_id") ?: continue
        val deviceId = o.str("device_id") ?: continue
        deviceByEntity[entityId] = deviceId
        entityIdsByDevice.getOrPut(deviceId) { mutableListOf() }.add(entityId)
    }

    val out = LinkedHashMap<String, DeviceRecord>()
    for (el in devices) {
        val o = el as? JsonObject ?: continue
        val id = o.str("id") ?: continue
        val area = o.str("area_id")?.let { nameByArea[it] }
        val name = o.str("name_by_user")?.trim()?.ifEmpty { null }
            ?: o.str("name")?.trim()?.ifEmpty { null }
            ?: "Device"
        out[id] = DeviceRecord(id, name, area, entityIdsByDevice[id] ?: emptyList())
    }
    return DeviceIndex(out, deviceByEntity)
}

/** HA's integration platform id for Z-Wave JS entities. */
const val ZWAVE_PLATFORM = "zwave_js"

/**
 * The entity ids owned by the Z-Wave JS integration (`platform == "zwave_js"`),
 * parsed from `config/entity_registry/list`. Used to detect a controller/radio
 * outage: if every Z-Wave entity reports `unavailable` at once, the stick or
 * zwave-js-ui is down — not one dead node. Mirrors web `buildZWaveEntityIds`.
 */
fun buildZWaveEntityIds(entities: JsonArray): List<String> {
    val out = mutableListOf<String>()
    for (el in entities) {
        val o = el as? JsonObject ?: continue
        if (o.str("platform") != ZWAVE_PLATFORM) continue
        o.str("entity_id")?.let { out.add(it) }
    }
    return out
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
