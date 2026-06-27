package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.stringAttr
import kotlin.math.roundToInt

/** Which at-a-glance highlight a room card shows; the UI maps each [RoomStat] to an icon. */
enum class RoomStat { UNLOCKED, MOTION, LIGHTS, CAMERAS, TEMPERATURE }

/** One room highlight chip: a [stat] kind, a short [label], and the PULSE [channel] tinting it. */
data class RoomHighlight(val stat: RoomStat, val label: String, val channel: Channel)

/** binary_sensor `device_class`es that count as "motion" for a room glance. */
private val MOTION_CLASSES = setOf("motion", "occupancy", "presence")

private const val MAX_HIGHLIGHTS = 4

/**
 * Compute up to [MAX_HIGHLIGHTS] meaningful highlights for a room from its (already diagnostics-
 * filtered) entities, in priority order: unlocked locks → motion → lights on → cameras →
 * temperature. Each appears only when present/non-zero, so a quiet room yields an empty list and the
 * card falls back to a plain device count. Pure + unit-testable; mirrors `src/lib/rooms.ts`.
 */
fun roomHighlights(entities: List<HassEntity>): List<RoomHighlight> {
    val out = mutableListOf<RoomHighlight>()

    val unlocked = entities.count {
        domainOf(it.entityId) == "lock" && it.state != "locked" && it.state != "locking"
    }
    if (unlocked > 0) out += RoomHighlight(RoomStat.UNLOCKED, "$unlocked unlocked", Channel.STREAK)

    val motion = entities.any {
        domainOf(it.entityId) == "binary_sensor" &&
            it.stringAttr("device_class") in MOTION_CLASSES && it.state == "on"
    }
    if (motion) out += RoomHighlight(RoomStat.MOTION, "Motion", Channel.STREAK)

    val lightsOn = entities.count { domainOf(it.entityId) == "light" && it.state == "on" }
    if (lightsOn > 0) out += RoomHighlight(RoomStat.LIGHTS, "$lightsOn on", Channel.STRENGTH)

    // resolveCameras collapses ring-mqtt's split entities so the count is per physical camera.
    val cameras = resolveCameras(entities.associateBy { it.entityId }).size
    if (cameras > 0) out += RoomHighlight(RoomStat.CAMERAS, cameras.toString(), Channel.EFFORT)

    roomTemperature(entities)?.let {
        out += RoomHighlight(RoomStat.TEMPERATURE, "$it°", Channel.EFFORT)
    }

    return out.take(MAX_HIGHLIGHTS)
}

/** The first usable temperature reading in the room (rounded), or null. */
private fun roomTemperature(entities: List<HassEntity>): Int? =
    entities.firstOrNull {
        domainOf(it.entityId) == "sensor" &&
            it.stringAttr("device_class") == "temperature" &&
            it.state.toDoubleOrNull() != null
    }?.state?.toDoubleOrNull()?.roundToInt()

/**
 * Map an area name to a stable icon key; the UI resolves it to a platform icon. Substring matching so
 * "Bedroom 2"/"Front Room"/"Backyard" land on sensible icons. Mirrors `src/lib/rooms.ts`.
 */
fun roomIconKey(area: String): String {
    val a = area.lowercase()
    return when {
        "kitchen" in a -> "kitchen"
        "dining" in a -> "dining"
        "bath" in a || "shower" in a -> "bath"
        "bed" in a || "master" in a || "nursery" in a -> "bedroom"
        "garage" in a -> "garage"
        "office" in a || "study" in a || "desk" in a -> "office"
        "living" in a || "family" in a || "lounge" in a || "great room" in a ||
            "front room" in a || "big room" in a || "den" in a -> "living"
        "basement" in a || "cellar" in a -> "basement"
        "laundry" in a || "utility" in a || "mud" in a -> "laundry"
        "front door" in a || "porch" in a || "entry" in a || "foyer" in a -> "frontdoor"
        "back" in a || "yard" in a || "exterior" in a || "outside" in a ||
            "patio" in a || "deck" in a || "garden" in a -> "outdoor"
        "security" in a || "alarm" in a -> "security"
        "unassigned" in a -> "unassigned"
        else -> "default"
    }
}
