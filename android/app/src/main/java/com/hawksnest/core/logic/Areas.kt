package com.hawksnest.core.logic

import com.hawksnest.core.ha.AreaRegistry
import com.hawksnest.core.ha.HassEntity

data class AreaGroup(val area: String, val entities: List<HassEntity>)

private val DEFAULT_ORDER = listOf("Front Door", "Back Door", "Basement", "Security")

/**
 * Group entities by their assigned area (from the area registry). Entities with no area land in
 * "Unassigned". A preferred [order] floats known areas to the top; the rest follow
 * alphabetically. [hidden] (personalization) drops entities the user hid so counts agree.
 *
 * Ported from `src/lib/areas.ts`.
 */
fun groupByArea(
    entities: List<HassEntity>,
    areas: AreaRegistry,
    order: List<String> = DEFAULT_ORDER,
    hidden: Set<String> = emptySet(),
): List<AreaGroup> {
    val groups = LinkedHashMap<String, MutableList<HassEntity>>()
    for (entity in entities) {
        if (entity.entityId in hidden) continue
        val area = areas[entity.entityId] ?: "Unassigned"
        groups.getOrPut(area) { mutableListOf() }.add(entity)
    }
    return groups.keys
        .sortedWith(Comparator { a, b ->
            val ai = order.indexOf(a)
            val bi = order.indexOf(b)
            if (ai != -1 || bi != -1) {
                (if (ai == -1) 99 else ai) - (if (bi == -1) 99 else bi)
            } else {
                a.compareTo(b)
            }
        })
        .map { AreaGroup(it, groups.getValue(it)) }
}
