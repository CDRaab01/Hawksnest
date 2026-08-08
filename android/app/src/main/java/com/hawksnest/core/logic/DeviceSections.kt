package com.hawksnest.core.logic

/**
 * The Devices redesign's three-tier rhythm: a room shows its FEATURED devices as
 * full cards (rich, deliberate controls), its CONTROL devices as compact rows
 * with an inline action, and its READONLY devices as quiet state rows at the
 * end. Cards mean "important", rows mean "inventory" — that contrast, applied
 * identically in every room, is what makes the list read as designed rather
 * than generated.
 */
enum class DeviceTier { FEATURED, CONTROL, READONLY }

fun tierOf(card: CardType): DeviceTier = when (card) {
    CardType.LOCK, CardType.CLIMATE, CardType.ALARM -> DeviceTier.FEATURED
    CardType.LIGHT, CardType.SWITCH, CardType.FAN,
    CardType.COVER, CardType.MEDIA_PLAYER -> DeviceTier.CONTROL
    else -> DeviceTier.READONLY
}

/**
 * One rendered row in a room's READONLY tier. The tier is *device*-aggregated
 * (since 2026-08-07): a physical device shedding several read-only entities — a
 * camera is the extreme case, 12–18 in this house — renders as one [Group] row
 * that opens a member sheet, instead of one row per entity. Entities whose
 * device is unknown, or whose device contributes only one read-only entity,
 * stay as [Single] rows. FEATURED/CONTROL tiers are untouched: they are ~1:1
 * with their devices already, and an inline control must never be buried in a
 * group.
 */
sealed interface ReadonlyItem<T> {
    data class Single<T>(val device: T) : ReadonlyItem<T>
    data class Group<T>(
        /** The HA device-registry id the members share. */
        val key: String,
        val name: String,
        val members: List<T>,
        /** How many members are currently active (for the "N sensors · M active" caption). */
        val activeCount: Int,
    ) : ReadonlyItem<T>
}

/** A group's or single's display name, for the shared alphabetical sort. */
fun <T> ReadonlyItem<T>.sortName(nameOf: (T) -> String): String = when (this) {
    is ReadonlyItem.Single -> nameOf(device)
    is ReadonlyItem.Group -> name
}

/** The underlying entities, whether one or many. */
fun <T> ReadonlyItem<T>.entities(): List<T> = when (this) {
    is ReadonlyItem.Single -> listOf(device)
    is ReadonlyItem.Group -> members
}

/** One room's slice of the Devices list, pre-sorted for rendering. */
data class DeviceSection<T>(
    val area: String,
    val featured: List<T>,
    val controls: List<T>,
    /** READONLY tier, device-aggregated — see [ReadonlyItem]. */
    val readonlyItems: List<ReadonlyItem<T>>,
    /** How many of the room's devices are currently active (for "3 devices · 1 on"). */
    val activeCount: Int,
    /**
     * Rendered count: featured + controls + readonly *items* (a group counts once).
     * This is what turns "Nursery — 18 devices" into an honest "Nursery — 4 devices"
     * when 15 of the 18 are one camera's sensor spray.
     */
    val total: Int,
)

const val UNASSIGNED_AREA = "Unassigned"

/** States that count as "active" for the per-room "N on" summaries. */
val DEVICE_ACTIVE_STATES: Set<String> =
    setOf("on", "unlocked", "open", "opening", "playing", "heat", "cool")

/**
 * Group devices into per-room sections: rooms alphabetical with [UNASSIGNED_AREA]
 * always last; inside a room, tiers in FEATURED → CONTROL → READONLY order.
 * FEATURED/CONTROL are alphabetical by display name; READONLY is device-aggregated
 * (see [ReadonlyItem]) with groups and singles sorted together alphabetically.
 *
 * [query] (the search field) filters by name, case-insensitively; empty keeps
 * everything. **A non-blank query bypasses grouping** — matches render as flat
 * [ReadonlyItem.Single]s, because a search hit must be one tap from its entity
 * detail, never buried behind a group sheet.
 *
 * Pure — the ViewModel adapts its UI models through the lambdas. [deviceKeyOf]
 * returns the HA device-registry id (null = no device, never grouped);
 * [deviceNameOf] resolves a device id to its display name.
 */
fun <T> buildDeviceSections(
    devices: List<T>,
    areaOf: (T) -> String?,
    cardOf: (T) -> CardType,
    nameOf: (T) -> String,
    isActive: (T) -> Boolean,
    query: String = "",
    deviceKeyOf: (T) -> String? = { null },
    deviceNameOf: (String) -> String = { it },
): List<DeviceSection<T>> {
    val q = query.trim()
    val searching = q.isNotEmpty()
    val visible = if (!searching) devices
    else devices.filter { nameOf(it).contains(q, ignoreCase = true) }

    return visible
        .groupBy { areaOf(it)?.takeIf { a -> a.isNotBlank() } ?: UNASSIGNED_AREA }
        .map { (area, inRoom) ->
            val byTier = inRoom.groupBy { tierOf(cardOf(it)) }
            fun tier(t: DeviceTier) =
                (byTier[t] ?: emptyList()).sortedBy { nameOf(it).lowercase() }
            val featured = tier(DeviceTier.FEATURED)
            val controls = tier(DeviceTier.CONTROL)
            val readonlyItems = buildReadonlyItems(
                tier(DeviceTier.READONLY), nameOf, isActive,
                deviceKeyOf = if (searching) { _ -> null } else deviceKeyOf,
                deviceNameOf = deviceNameOf,
            )
            DeviceSection(
                area = area,
                featured = featured,
                controls = controls,
                readonlyItems = readonlyItems,
                activeCount = inRoom.count(isActive),
                total = featured.size + controls.size + readonlyItems.size,
            )
        }
        .sortedWith(
            compareBy({ it.area == UNASSIGNED_AREA }, { it.area.lowercase() }),
        )
}

/** Aggregate a room's sorted READONLY entities into groups-of-a-device and singles. */
private fun <T> buildReadonlyItems(
    readonly: List<T>,
    nameOf: (T) -> String,
    isActive: (T) -> Boolean,
    deviceKeyOf: (T) -> String?,
    deviceNameOf: (String) -> String,
): List<ReadonlyItem<T>> {
    val items = mutableListOf<ReadonlyItem<T>>()
    for ((key, members) in readonly.groupBy(deviceKeyOf)) {
        if (key == null || members.size < 2) {
            members.mapTo(items) { ReadonlyItem.Single(it) }
        } else {
            items += ReadonlyItem.Group(
                key = key,
                name = deviceNameOf(key),
                members = members,
                activeCount = members.count(isActive),
            )
        }
    }
    return items.sortedBy { it.sortName(nameOf).lowercase() }
}

/**
 * Apply a per-device predicate (the chip filter) to already-built sections:
 * tiers filter element-wise, groups filter member-wise (a partially matching
 * group shrinks — or degrades to a [ReadonlyItem.Single] at one member — and an
 * empty one disappears), and a room with nothing left disappears entirely.
 * `total` and `activeCount` are recomputed so room summaries stay honest under
 * a filter.
 */
fun <T> filterSections(
    sections: List<DeviceSection<T>>,
    isActive: (T) -> Boolean,
    predicate: (T) -> Boolean,
): List<DeviceSection<T>> = sections.mapNotNull { s ->
    val f = s.featured.filter(predicate)
    val c = s.controls.filter(predicate)
    val r = s.readonlyItems.mapNotNull { item ->
        when (item) {
            is ReadonlyItem.Single -> item.takeIf { predicate(item.device) }
            is ReadonlyItem.Group -> {
                val members = item.members.filter(predicate)
                when {
                    members.isEmpty() -> null
                    members.size == 1 -> ReadonlyItem.Single(members.single())
                    else -> item.copy(members = members, activeCount = members.count(isActive))
                }
            }
        }
    }
    if (f.isEmpty() && c.isEmpty() && r.isEmpty()) null
    else {
        val kept = f + c + r.flatMap {
            when (it) {
                is ReadonlyItem.Single -> listOf(it.device)
                is ReadonlyItem.Group -> it.members
            }
        }
        s.copy(
            featured = f,
            controls = c,
            readonlyItems = r,
            activeCount = kept.count(isActive),
            total = f.size + c.size + r.size,
        )
    }
}
