package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf

/**
 * One logical camera, independent of how the backend models it. **ring-mqtt**
 * splits a Ring camera across several HA entities (`camera.<base>_snapshot`,
 * `select.<base>_event_select`, `binary_sensor.<base>_motion`/`_ding`, plus
 * `camera.<base>_live`/`_event` on older ring-mqtt); this collapses them into one
 * camera. Plain HA / Frigate cameras map 1:1. Ported from `src/lib/cameraModel.ts`.
 */
data class LogicalCamera(
    val id: String,
    val name: String,
    val liveEntity: HassEntity,
    val snapshotEntity: HassEntity,
    /**
     * Recorded-event playback stream entity (`camera.<base>_event`) — ring-mqtt 4.x only. 5.x
     * publishes the selected event's recording as the selector's `recordingUrl` attribute instead,
     * so this is null on current deployments and recorded playback goes through that.
     */
    val eventStreamId: String?,
    val eventSelectId: String?,
    /** Doorbell press sensor (ring-mqtt `_ding` or Reolink `_visitor`), if present. */
    val dingId: String?,
    val motionId: String?,
    /** ring-mqtt siren switch (`switch.<base>_siren`) on siren-capable cameras, else null. */
    val sirenSwitchId: String? = null,
)

/**
 * Doorbell-press sensor suffixes, in preference order.
 *
 * ring-mqtt names the press `binary_sensor.<base>_ding`; HA's official **Reolink**
 * integration names the identical signal `binary_sensor.<base>_visitor`. Both mean
 * "someone pressed the button", so both resolve into the same [LogicalCamera.dingId]
 * and every downstream consumer works unchanged whichever backend the doorbell came
 * from. Keep in lockstep with `src/lib/cameraModel.ts`'s `DING_SUFFIXES`.
 */
private val DING_SUFFIXES = listOf("_ding", "_visitor")

/** States meaning "this entity is registered but not reporting". */
private val DEAD_STATES = setOf("unavailable", "unknown")

private fun objectIdOf(entityId: String): String =
    entityId.substringAfter('.', entityId)

private data class Classified(val base: String, val role: String)

private fun classify(objectId: String): Classified = when {
    // HA's official Ring integration adds a dedicated live-view entity
    // (`camera.<base>_live_view`, "X Live view") alongside the snapshot camera — treat it as the
    // live feed so it folds into the base camera instead of becoming a second tile.
    objectId.endsWith("_live_view") -> Classified(objectId.removeSuffix("_live_view"), "live")
    objectId.endsWith("_live") -> Classified(objectId.removeSuffix("_live"), "live")
    objectId.endsWith("_snapshot") -> Classified(objectId.removeSuffix("_snapshot"), "snapshot")
    objectId.endsWith("_event") -> Classified(objectId.removeSuffix("_event"), "event")
    else -> Classified(objectId, "standalone")
}

private val ROLE_SUFFIX = Regex("\\s+(Live view|Live|Snapshot|Event)$", RegexOption.IGNORE_CASE)

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

    /**
     * The doorbell-press sensor for [base], preferring one that is actually reporting.
     *
     * Retiring a backend does not unregister its entities: a replaced Ring doorbell leaves
     * `binary_sensor.<base>_ding` registered but `unavailable`, still holding the canonical
     * slug, while the replacement publishes `_visitor`. Picking by suffix order alone would
     * bind the dead sensor and the banner would never fire. First live candidate wins; fall
     * back to declaration order only when none are reporting.
     */
    fun dingIdFor(base: String): String? {
        val candidates = DING_SUFFIXES.mapNotNull { has("binary_sensor.$base$it") }
        return candidates.firstOrNull { entities[it]?.state !in DEAD_STATES }
            ?: candidates.firstOrNull()
    }

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
                dingId = dingIdFor(base),
                motionId = has("binary_sensor.${base}_motion"),
                sirenSwitchId = has("switch.${base}_siren"),
            ),
        )
    }
    return cameras.sortedBy { it.id }
}
