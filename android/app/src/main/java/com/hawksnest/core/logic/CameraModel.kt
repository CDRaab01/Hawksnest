package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf

/**
 * One logical camera, independent of how the backend models it. **ring-mqtt**
 * splits a Ring camera across several HA entities (`camera.<base>_live`,
 * `_snapshot`, `_event`, `select.<base>_event_select`, `binary_sensor.<base>_motion`/
 * `_ding`); this collapses them into one camera. Plain HA / Frigate cameras map
 * 1:1. Ported from `src/lib/cameraModel.ts`.
 */
data class LogicalCamera(
    val id: String,
    val name: String,
    val liveEntity: HassEntity,
    val snapshotEntity: HassEntity,
    val eventStreamId: String?,
    val eventSelectId: String?,
    val dingId: String?,
    val motionId: String?,
)

private fun objectIdOf(entityId: String): String =
    entityId.substringAfter('.', entityId)

private data class Classified(val base: String, val role: String)

private fun classify(objectId: String): Classified = when {
    objectId.endsWith("_live") -> Classified(objectId.removeSuffix("_live"), "live")
    objectId.endsWith("_snapshot") -> Classified(objectId.removeSuffix("_snapshot"), "snapshot")
    objectId.endsWith("_event") -> Classified(objectId.removeSuffix("_event"), "event")
    else -> Classified(objectId, "standalone")
}

private val ROLE_SUFFIX = Regex("\\s+(Live|Snapshot|Event)$", RegexOption.IGNORE_CASE)

/** Strip a trailing role word ring-mqtt appends to friendly names ("Front Door Live"). */
private fun cleanName(name: String): String = name.replace(ROLE_SUFFIX, "")

/**
 * Collapse all `camera.*` entities into logical cameras, binding each ring-mqtt
 * camera's sibling entities (event stream/selector, motion/ding) by base name.
 * Sorted by id for a stable wall order.
 */
fun resolveCameras(
    entities: Map<String, HassEntity>,
    overrides: OverrideMap = emptyMap(),
): List<LogicalCamera> {
    val groups = LinkedHashMap<String, MutableMap<String, HassEntity>>()
    for (entity in entities.values) {
        if (domainOf(entity.entityId) != "camera") continue
        val (base, role) = classify(objectIdOf(entity.entityId))
        groups.getOrPut(base) { mutableMapOf() }[role] = entity
    }

    fun has(id: String): String? = if (entities.containsKey(id)) id else null

    val cameras = mutableListOf<LogicalCamera>()
    for ((base, g) in groups) {
        val liveEntity = g["live"] ?: g["standalone"] ?: g["snapshot"] ?: continue
        val snapshotEntity = g["snapshot"] ?: g["standalone"] ?: g["live"] ?: continue
        cameras.add(
            LogicalCamera(
                id = "camera.$base",
                name = cleanName(resolveName(liveEntity, overrides)),
                liveEntity = liveEntity,
                snapshotEntity = snapshotEntity,
                eventStreamId = g["event"]?.entityId,
                eventSelectId = has("select.${base}_event_select"),
                dingId = has("binary_sensor.${base}_ding"),
                motionId = has("binary_sensor.${base}_motion"),
            ),
        )
    }
    return cameras.sortedBy { it.id }
}
