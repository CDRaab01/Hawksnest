package com.hawksnest.core.logic

/**
 * Devices v3 — the control deck. The Devices tab regrouped by FUNCTION and ordered by
 * IMPORTANCE, instead of by room: the Rooms tab already browses by place, so this tab's
 * job is "what can I do", with the things that matter most (locks, alarm) always first
 * and the things read rarely (sensor spray) summarized at the bottom. Section order is
 * fixed on purpose — the hierarchy IS the design; there is no per-user section config.
 *
 * Search bypasses the deck exactly like it bypasses room grouping: a non-blank query
 * returns flat [ControlDeck.searchResults] and every other field empty — a hit must be
 * one tap from its entity detail.
 */
data class ControlDeck<T>(
    /** Offline / low-battery devices — a warning strip above everything; often empty. */
    val attention: List<T> = emptyList(),
    /** LOCK + ALARM, full control cards. Locks first, then alarm, alphabetical within. */
    val security: List<T> = emptyList(),
    /** LIGHT + SWITCH — the tile grid. */
    val lights: List<T> = emptyList(),
    /** CLIMATE (full cards) + FAN (rows). */
    val climate: List<T> = emptyList(),
    /** COVER — empty in this house today; the section renders only when non-empty. */
    val covers: List<T> = emptyList(),
    /** MEDIA_PLAYER rows. */
    val media: List<T> = emptyList(),
    /** Devices whose read-only group contains a camera — behind one summary row. */
    val cameraGroups: List<ReadonlyItem.Group<T>> = emptyList(),
    /** Everything else read-only, grouped per room — behind one summary row. */
    val sensorSections: List<DeviceSection<T>> = emptyList(),
    /** Flat matches when [buildControlDeck]'s query is non-blank; all else empty then. */
    val searchResults: List<T> = emptyList(),
) {
    val isSearch: Boolean get() = searchResults.isNotEmpty()
}

/**
 * The attention rule. MEASURED against the live house 2026-08-08, because the first
 * version was wrong in both directions:
 *
 * - "unknown" does NOT mean offline. Sirens, PTZ-preset selects and similar stateless
 *   entities rest at "unknown" forever — the first rule would have pinned 13 healthy
 *   devices to the strip permanently. Only "unavailable" (the integration lost the
 *   device) counts, and only when the WHOLE device is unavailable (see
 *   [buildControlDeck]) — a single unavailable member on an otherwise-healthy device is
 *   normal (e.g. WLED's playlist select with no playlists saved).
 * - Battery health lives in DIAGNOSTIC entities the tab rightly filters out (a Ring
 *   sensor's `*_battery` is `entity_category: diagnostic`), so a dead sensor would never
 *   have surfaced. The ViewModel scans ALL entities — pre-filter — for battery signals
 *   (a `device_class: battery` sensor's own state, or a `battery_level` attribute) and
 *   flags the device; verified against the real failure it must catch (Upstairs Motion:
 *   batteryStatus "failed", last comm two months stale, battery sensor diagnostic).
 */
fun needsAttention(rawState: String, batteryLevel: Double?): Boolean =
    rawState == "unavailable" || (batteryLevel != null && batteryLevel <= 20.0)

/**
 * Build the deck. Pure — the ViewModel adapts its UI models through the lambdas, exactly
 * like [buildDeviceSections] (which this reuses for the per-room sensor tail, so the
 * grouping rules cannot fork).
 */
fun <T> buildControlDeck(
    devices: List<T>,
    areaOf: (T) -> String?,
    cardOf: (T) -> CardType,
    nameOf: (T) -> String,
    isActive: (T) -> Boolean,
    attentionOf: (T) -> Boolean,
    offlineOf: (T) -> Boolean,
    query: String = "",
    deviceKeyOf: (T) -> String? = { null },
    deviceNameOf: (String) -> String = { it },
): ControlDeck<T> {
    val q = query.trim()
    if (q.isNotEmpty()) {
        return ControlDeck(
            searchResults = devices
                .filter { nameOf(it).contains(q, ignoreCase = true) }
                .sortedBy { nameOf(it).lowercase() },
        )
    }

    val byName = compareBy<T> { nameOf(it).lowercase() }
    val byCard = devices.groupBy { cardOf(it) }
    fun of(vararg cards: CardType): List<T> =
        cards.flatMap { byCard[it].orEmpty() }.sortedWith(byName)

    // Attention, device-level: a device is offline only when EVERY visible entity of it is
    // unavailable; battery flags come in via attentionOf (the ViewModel's pre-filter scan).
    // One row per flagged device (the alphabetically-first member represents it) — a dead
    // device must not flood the strip with each of its entities.
    val attention = run {
        val flagged = mutableListOf<T>()
        val byDevice = devices.groupBy(deviceKeyOf)
        for ((key, members) in byDevice) {
            if (key == null) {
                // No device identity — evaluate each entity on its own.
                flagged += members.filter { attentionOf(it) || offlineOf(it) }
            } else {
                val allOffline = members.all(offlineOf)
                if (allOffline || members.any(attentionOf)) {
                    flagged += members.sortedWith(byName).first()
                }
            }
        }
        flagged.sortedWith(byName)
    }

    // Locks before the alarm: the deck's one intra-section ordering opinion — a lock is
    // the thing you act on; the alarm summarizes.
    val security = of(CardType.LOCK) + of(CardType.ALARM)

    // Everything read-only, grouped per device once; camera-bearing groups split off to
    // their own summary, the rest keep their per-room shape.
    val controlCards = setOf(
        CardType.LOCK, CardType.ALARM, CardType.LIGHT, CardType.SWITCH,
        CardType.CLIMATE, CardType.FAN, CardType.COVER, CardType.MEDIA_PLAYER,
    )
    val readonly = devices.filter { cardOf(it) !in controlCards }
    val readonlySections = buildDeviceSections(
        devices = readonly,
        areaOf = areaOf,
        cardOf = cardOf,
        nameOf = nameOf,
        isActive = isActive,
        deviceKeyOf = deviceKeyOf,
        deviceNameOf = deviceNameOf,
    )
    val cameraGroups = mutableListOf<ReadonlyItem.Group<T>>()
    val sensorSections = readonlySections.mapNotNull { section ->
        val (cams, rest) = section.readonlyItems.partition { item ->
            item is ReadonlyItem.Group && item.members.any { cardOf(it) == CardType.CAMERA }
        }
        cams.forEach { cameraGroups += it as ReadonlyItem.Group<T> }
        if (rest.isEmpty()) null
        else section.copy(
            readonlyItems = rest,
            total = rest.size,
            activeCount = rest.sumOf { item ->
                when (item) {
                    is ReadonlyItem.Single -> if (isActive(item.device)) 1 else 0
                    is ReadonlyItem.Group -> item.activeCount
                }
            },
        )
    }
    cameraGroups.sortBy { it.name.lowercase() }

    return ControlDeck(
        attention = attention,
        security = security,
        lights = of(CardType.LIGHT, CardType.SWITCH),
        climate = of(CardType.CLIMATE, CardType.FAN),
        covers = of(CardType.COVER),
        media = of(CardType.MEDIA_PLAYER),
        cameraGroups = cameraGroups,
        sensorSections = sensorSections,
    )
}
