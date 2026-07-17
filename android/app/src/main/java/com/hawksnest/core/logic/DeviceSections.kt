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

/** One room's slice of the Devices list, pre-sorted for rendering. */
data class DeviceSection<T>(
    val area: String,
    val featured: List<T>,
    val controls: List<T>,
    val readonly: List<T>,
    /** How many of the room's devices are currently active (for "3 devices · 1 on"). */
    val activeCount: Int,
    val total: Int,
)

const val UNASSIGNED_AREA = "Unassigned"

/**
 * Group devices into per-room sections: rooms alphabetical with [UNASSIGNED_AREA]
 * always last; inside a room, tiers in FEATURED → CONTROL → READONLY order, each
 * tier alphabetical by display name. [query] (the search field) filters by name,
 * case-insensitively; empty keeps everything. Pure — the ViewModel adapts its UI
 * models through the lambdas.
 */
fun <T> buildDeviceSections(
    devices: List<T>,
    areaOf: (T) -> String?,
    cardOf: (T) -> CardType,
    nameOf: (T) -> String,
    isActive: (T) -> Boolean,
    query: String = "",
): List<DeviceSection<T>> {
    val q = query.trim()
    val visible = if (q.isEmpty()) devices
    else devices.filter { nameOf(it).contains(q, ignoreCase = true) }

    return visible
        .groupBy { areaOf(it)?.takeIf { a -> a.isNotBlank() } ?: UNASSIGNED_AREA }
        .map { (area, inRoom) ->
            val byTier = inRoom.groupBy { tierOf(cardOf(it)) }
            fun tier(t: DeviceTier) =
                (byTier[t] ?: emptyList()).sortedBy { nameOf(it).lowercase() }
            DeviceSection(
                area = area,
                featured = tier(DeviceTier.FEATURED),
                controls = tier(DeviceTier.CONTROL),
                readonly = tier(DeviceTier.READONLY),
                activeCount = inRoom.count(isActive),
                total = inRoom.size,
            )
        }
        .sortedWith(
            compareBy({ it.area == UNASSIGNED_AREA }, { it.area.lowercase() }),
        )
}
