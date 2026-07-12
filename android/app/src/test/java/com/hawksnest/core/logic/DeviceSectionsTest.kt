package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSectionsTest {

    private data class D(
        val name: String,
        val area: String?,
        val card: CardType,
        val active: Boolean = false,
    )

    private fun sections(devices: List<D>, query: String = "") =
        buildDeviceSections(
            devices = devices,
            areaOf = { it.area },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.active },
            query = query,
        )

    @Test
    fun `rooms sort alphabetically with Unassigned always last`() {
        val out = sections(
            listOf(
                D("A", null, CardType.LIGHT),
                D("B", "Kitchen", CardType.LIGHT),
                D("C", "Bedroom", CardType.LIGHT),
            ),
        )
        assertEquals(listOf("Bedroom", "Kitchen", UNASSIGNED_AREA), out.map { it.area })
    }

    @Test
    fun `tiers split featured - controls - readonly, alphabetical within each`() {
        val out = sections(
            listOf(
                D("Zed Sensor", "Hall", CardType.BINARY_SENSOR),
                D("Front Door", "Hall", CardType.LOCK),
                D("Beta Light", "Hall", CardType.LIGHT, active = true),
                D("Alpha Light", "Hall", CardType.LIGHT),
                D("Thermostat", "Hall", CardType.CLIMATE),
            ),
        ).single()
        assertEquals(listOf("Front Door", "Thermostat"), out.featured.map { it.name })
        assertEquals(listOf("Alpha Light", "Beta Light"), out.controls.map { it.name })
        assertEquals(listOf("Zed Sensor"), out.readonly.map { it.name })
        assertEquals(1, out.activeCount)
        assertEquals(5, out.total)
    }

    @Test
    fun `search filters by name across all tiers, case-insensitively`() {
        val out = sections(
            listOf(
                D("Front Light", "Yard", CardType.LIGHT),
                D("Back Light", "Yard", CardType.LIGHT),
                D("Front Door", "Hall", CardType.LOCK),
            ),
            query = "front",
        )
        assertEquals(
            listOf("Front Door", "Front Light"),
            out.flatMap { it.featured + it.controls + it.readonly }.map { it.name }.sorted(),
        )
    }

    @Test
    fun `blank area lands in Unassigned`() {
        val out = sections(listOf(D("X", "  ", CardType.LIGHT)))
        assertEquals(UNASSIGNED_AREA, out.single().area)
    }
}
