package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSectionsTest {

    private data class D(
        val name: String,
        val area: String?,
        val card: CardType,
        val active: Boolean = false,
        val deviceId: String? = null,
    )

    private val deviceNames = mapOf(
        "cam1" to "Nursery Camera",
        "cam2" to "Kitchen Camera",
        "sc1" to "Scene Controller",
    )

    private fun sections(devices: List<D>, query: String = "") =
        buildDeviceSections(
            devices = devices,
            areaOf = { it.area },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.active },
            query = query,
            deviceKeyOf = { it.deviceId },
            deviceNameOf = { deviceNames[it] ?: it },
        )

    private fun ReadonlyItem<D>.names(): List<String> = when (this) {
        is ReadonlyItem.Single -> listOf(device.name)
        is ReadonlyItem.Group -> members.map { it.name }
    }

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
        assertEquals(listOf(listOf("Zed Sensor")), out.readonlyItems.map { it.names() })
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
            out.flatMap { s ->
                s.featured.map { it.name } + s.controls.map { it.name } +
                    s.readonlyItems.flatMap { it.names() }
            }.sorted(),
        )
    }

    @Test
    fun `blank area lands in Unassigned`() {
        val out = sections(listOf(D("X", "  ", CardType.LIGHT)))
        assertEquals(UNASSIGNED_AREA, out.single().area)
    }

    // ── Device-aggregated READONLY tier ──────────────────────────────────────

    @Test
    fun `readonly entities sharing a device collapse into one group with member sort and active count`() {
        val out = sections(
            listOf(
                D("Zed Motion", "Nursery", CardType.BINARY_SENSOR, active = true, deviceId = "cam1"),
                D("Alpha Person", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("Mid Cam", "Nursery", CardType.CAMERA, active = true, deviceId = "cam1"),
            ),
        ).single()
        val group = out.readonlyItems.single() as ReadonlyItem.Group
        assertEquals("cam1", group.key)
        assertEquals("Nursery Camera", group.name)
        assertEquals(listOf("Alpha Person", "Mid Cam", "Zed Motion"), group.members.map { it.name })
        assertEquals(2, group.activeCount)
    }

    @Test
    fun `singleton devices and null device keys stay Single`() {
        val out = sections(
            listOf(
                D("Lone Sensor", "Hall", CardType.BINARY_SENSOR, deviceId = "sc1"),
                D("Orphan Sensor", "Hall", CardType.BINARY_SENSOR, deviceId = null),
            ),
        ).single()
        assertEquals(2, out.readonlyItems.size)
        assertTrue(out.readonlyItems.all { it is ReadonlyItem.Single })
    }

    @Test
    fun `groups and singles sort together alphabetically by display name`() {
        val out = sections(
            listOf(
                D("Aardvark Sensor", "Hall", CardType.BINARY_SENSOR),
                D("x1", "Hall", CardType.BINARY_SENSOR, deviceId = "cam2"),
                D("x2", "Hall", CardType.BINARY_SENSOR, deviceId = "cam2"),
                D("Zebra Sensor", "Hall", CardType.BINARY_SENSOR),
            ),
        ).single()
        // "Aardvark Sensor" < "Kitchen Camera" (group) < "Zebra Sensor"
        assertEquals(
            listOf("Aardvark Sensor", "Kitchen Camera", "Zebra Sensor"),
            out.readonlyItems.map {
                when (it) {
                    is ReadonlyItem.Single -> it.device.name
                    is ReadonlyItem.Group -> it.name
                }
            },
        )
    }

    @Test
    fun `a non-blank query bypasses grouping - hits are flat Singles`() {
        val out = sections(
            listOf(
                D("Nursery Motion", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("Nursery Person", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
            ),
            query = "nursery",
        ).single()
        assertEquals(2, out.readonlyItems.size)
        assertTrue(out.readonlyItems.all { it is ReadonlyItem.Single })
    }

    @Test
    fun `total counts a group once`() {
        val out = sections(
            listOf(
                D("Front Door", "Nursery", CardType.LOCK),
                D("Lamp", "Nursery", CardType.LIGHT),
                D("m1", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("m2", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("m3", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
            ),
        ).single()
        assertEquals(3, out.total) // lock + light + one camera group, not 5
    }

    @Test
    fun `area-detail shape - constant areaOf yields one section with controls plus groups`() {
        // The AreaDetailViewModel path: every device already scoped to one room, areaOf constant.
        val out = buildDeviceSections(
            devices = listOf(
                D("Lamp", "Nursery", CardType.LIGHT, active = true),
                D("Night light", "Nursery", CardType.LIGHT),
                D("m1", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("m2", "Nursery", CardType.CAMERA, deviceId = "cam1"),
                D("m3", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("Lone temp", "Nursery", CardType.GENERIC),
            ),
            areaOf = { "Nursery" },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.active },
            deviceKeyOf = { it.deviceId },
            deviceNameOf = { deviceNames[it] ?: it },
        ).single()
        assertEquals(listOf("Lamp", "Night light"), out.controls.map { it.name })
        assertEquals(2, out.readonlyItems.size) // one camera group + one lone sensor
        assertEquals(1, out.readonlyItems.filterIsInstance<ReadonlyItem.Group<D>>().size)
        assertEquals(4, out.total) // 2 controls + group-once + single
    }

    // ── filterSections (the chip filter) ─────────────────────────────────────

    private val chipMatchesSensors: (D) -> Boolean =
        { it.card in setOf(CardType.BINARY_SENSOR, CardType.GENERIC, CardType.CAMERA) }

    @Test
    fun `filterSections keeps matching group members and drops empty groups and rooms`() {
        val built = sections(
            listOf(
                D("Lamp", "Hall", CardType.LIGHT),
                D("m1", "Hall", CardType.BINARY_SENSOR, deviceId = "cam1"),
                D("m2", "Hall", CardType.CAMERA, deviceId = "cam1"),
                D("Solo Light", "Den", CardType.LIGHT),
            ),
        )
        val filtered = filterSections(built, isActive = { it.active }, predicate = chipMatchesSensors)
        // Den had only a light -> the room disappears.
        assertEquals(listOf("Hall"), filtered.map { it.area })
        val hall = filtered.single()
        assertEquals(0, hall.controls.size)
        val group = hall.readonlyItems.single() as ReadonlyItem.Group
        assertEquals(2, group.members.size)
        assertEquals(1, hall.total)
    }

    @Test
    fun `filterSections degrades a one-survivor group to a Single`() {
        val built = sections(
            listOf(
                D("Cam Feed", "Hall", CardType.CAMERA, deviceId = "cam1"),
                D("Cam Generic", "Hall", CardType.GENERIC, deviceId = "cam1"),
            ),
        )
        // Predicate keeps only the CAMERA member.
        val filtered = filterSections(
            built,
            isActive = { it.active },
            predicate = { it.card == CardType.CAMERA },
        )
        val item = filtered.single().readonlyItems.single()
        assertTrue(item is ReadonlyItem.Single)
        assertEquals("Cam Feed", (item as ReadonlyItem.Single).device.name)
    }

    @Test
    fun `filterSections recomputes total and activeCount honestly`() {
        val built = sections(
            listOf(
                D("Lamp", "Hall", CardType.LIGHT, active = true),
                D("Sensor", "Hall", CardType.BINARY_SENSOR, active = true),
            ),
        )
        val filtered = filterSections(built, isActive = { it.active }, predicate = chipMatchesSensors)
        val hall = filtered.single()
        assertEquals(1, hall.total)
        assertEquals(1, hall.activeCount) // the filtered-out active lamp no longer counts
    }
}
