package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the `src/lib/areas.ts` port (grouping, preferred ordering, unassigned, hidden). */
class AreasTest {

    private val entities = listOf(
        entity("lock.front_door_lock"),
        entity("light.kitchen"),
        entity("alarm_control_panel.home"),
        entity("sensor.attic_temp"),
    )
    private val areas = mapOf(
        "lock.front_door_lock" to "Front Door",
        "light.kitchen" to "Kitchen",
        "alarm_control_panel.home" to "Security",
        // sensor.attic_temp has no area -> "Unassigned"
    )

    @Test
    fun `groups by area and floats known areas to the top, rest alphabetical`() {
        val groups = groupByArea(entities, areas)
        // Front Door + Security are in DEFAULT_ORDER (float up, in that order); Kitchen and
        // Unassigned are not, so they sort alphabetically after.
        assertEquals(listOf("Front Door", "Security", "Kitchen", "Unassigned"), groups.map { it.area })
    }

    @Test
    fun `entities without an area land in Unassigned`() {
        val groups = groupByArea(entities, areas)
        val unassigned = groups.first { it.area == "Unassigned" }
        assertEquals(listOf("sensor.attic_temp"), unassigned.entities.map { it.entityId })
    }

    @Test
    fun `hidden entities are dropped before grouping`() {
        val groups = groupByArea(entities, areas, hidden = setOf("light.kitchen"))
        assertEquals(listOf("Front Door", "Security", "Unassigned"), groups.map { it.area })
    }
}
