package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDeckTest {

    private data class D(
        val name: String,
        val area: String?,
        val card: CardType,
        val active: Boolean = false,
        val deviceId: String? = null,
        val attention: Boolean = false,
        val offline: Boolean = false,
        /** Entity id, for the search cases; defaults to something derived from the name. */
        val id: String = "x." + name.lowercase().replace(' ', '_'),
    )

    private val deviceNames = mapOf("cam1" to "Nursery Camera", "sc1" to "Scene Controller")

    private fun deck(devices: List<D>, query: String = "") =
        buildControlDeck(
            devices = devices,
            areaOf = { it.area },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.active },
            attentionOf = { it.attention },
            offlineOf = { it.offline },
            query = query,
            deviceKeyOf = { it.deviceId },
            deviceNameOf = { deviceNames[it] ?: it },
            idOf = { it.id },
        )

    private val house = listOf(
        D("Front Door", "Front Door", CardType.LOCK),
        D("Back Door", "Back Door", CardType.LOCK),
        D("Alarm", "Security", CardType.ALARM),
        D("Lamp", "Nursery", CardType.LIGHT, active = true),
        D("Siren switch", "Security", CardType.SWITCH),
        D("Tower Fan", "Nursery", CardType.FAN, active = true),
        D("cam feed", "Nursery", CardType.CAMERA, deviceId = "cam1", active = true),
        D("cam motion", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
        D("cam person", "Nursery", CardType.BINARY_SENSOR, deviceId = "cam1"),
        D("Door sensor", "Front Door", CardType.BINARY_SENSOR),
        D("sc a", "Nursery", CardType.GENERIC, deviceId = "sc1"),
        D("sc b", "Nursery", CardType.GENERIC, deviceId = "sc1", active = true),
    )

    @Test
    fun `sections split by function - locks before alarm, lights include switches`() {
        val d = deck(house)
        assertEquals(listOf("Back Door", "Front Door", "Alarm"), d.security.map { it.name })
        assertEquals(listOf("Lamp", "Siren switch"), d.lights.map { it.name })
        assertEquals(listOf("Tower Fan"), d.climate.map { it.name })
        assertTrue(d.covers.isEmpty())
        assertTrue(d.media.isEmpty())
    }

    @Test
    fun `camera-bearing device groups split off - the rest keep per-room sensor sections`() {
        val d = deck(house)
        assertEquals(listOf("Nursery Camera"), d.cameraGroups.map { it.name })
        assertEquals(3, d.cameraGroups.single().members.size)
        // Sensor tail: Front Door's lone sensor + Nursery's scene-controller group.
        assertEquals(listOf("Front Door", "Nursery"), d.sensorSections.map { it.area })
        val nursery = d.sensorSections.single { it.area == "Nursery" }
        val group = nursery.readonlyItems.single() as ReadonlyItem.Group
        assertEquals("Scene Controller", group.name)
        assertEquals(1, group.activeCount)
        assertEquals(1, nursery.total)
        assertEquals(1, nursery.activeCount)
    }

    @Test
    fun `a room whose only readonly item was the camera drops out of the sensor tail`() {
        val d = deck(
            listOf(
                D("cam feed", "Kitchen", CardType.CAMERA, deviceId = "cam1"),
                D("cam motion", "Kitchen", CardType.BINARY_SENSOR, deviceId = "cam1"),
            ),
        )
        assertEquals(1, d.cameraGroups.size)
        assertTrue(d.sensorSections.isEmpty())
    }

    @Test
    fun `search returns flat results and nothing else`() {
        val d = deck(house, query = "door")
        assertEquals(listOf("Back Door", "Door sensor", "Front Door"), d.searchResults.map { it.name })
        assertTrue(d.security.isEmpty())
        assertTrue(d.cameraGroups.isEmpty())
        assertTrue(d.sensorSections.isEmpty())
        assertTrue(d.isSearch)
    }

    @Test
    fun `attention strip collects flagged devices and leaves them in their sections too`() {
        val d = deck(house.map { if (it.name == "Front Door") it.copy(attention = true) else it })
        assertEquals(listOf("Front Door"), d.attention.map { it.name })
        assertTrue(d.security.any { it.name == "Front Door" }) // a shortcut, not a re-org
    }

    @Test
    fun `a device is offline-flagged only when ALL its entities are unavailable, once`() {
        // Whole device down -> one representative row, not one per entity.
        val down = deck(
            listOf(
                D("Zed light", "Bed", CardType.LIGHT, deviceId = "wled1", offline = true),
                D("Alpha select", "Bed", CardType.GENERIC, deviceId = "wled1", offline = true),
            ),
        )
        assertEquals(listOf("Alpha select"), down.attention.map { it.name })

        // One unavailable member on a healthy device (WLED's empty playlist select) -> nothing.
        val healthy = deck(
            listOf(
                D("Light", "Bed", CardType.LIGHT, deviceId = "wled1"),
                D("Playlist", "Bed", CardType.GENERIC, deviceId = "wled1", offline = true),
            ),
        )
        assertTrue(healthy.attention.isEmpty())
    }

    @Test
    fun `an entity with no device is judged on its own`() {
        val d = deck(listOf(D("Orphan", "Hall", CardType.GENERIC, offline = true)))
        assertEquals(listOf("Orphan"), d.attention.map { it.name })
    }

    @Test
    fun `a battery-flagged device surfaces one representative even when its members look healthy`() {
        // The dead Ring sensor case: battery signal comes from a diagnostic entity the tab
        // filtered out, so the ViewModel flags the device; members themselves read normal.
        val d = deck(
            listOf(
                D("Upstairs Motion", "Hall", CardType.BINARY_SENSOR, deviceId = "ring1", attention = true),
                D("Bypass mode", "Hall", CardType.GENERIC, deviceId = "ring1", attention = true),
            ),
        )
        assertEquals(listOf("Bypass mode"), d.attention.map { it.name })
    }

    @Test
    fun `needsAttention rule - unavailable or battery at or under 20, unknown is a resting state`() {
        assertTrue(needsAttention("unavailable", null))
        assertEquals(false, needsAttention("unknown", null)) // sirens/selects rest here
        assertTrue(needsAttention("on", 20.0))
        assertTrue(needsAttention("on", 5.0))
        assertEquals(false, needsAttention("on", 21.0))
        assertEquals(false, needsAttention("off", null))
    }

    /**
     * A query that matched nothing rendered NOTHING.
     *
     * Every other field of the deck is empty for a non-blank query, and the screen keyed its
     * "am I searching" branch off `searchResults.isNotEmpty()` — so a search with no hits showed
     * a bare search box over blank space, indistinguishable from a house with no devices.
     */
    @Test
    fun `a search that matches nothing is still a search`() {
        val d = deck(house, query = "zzzz")
        assertTrue(d.searchResults.isEmpty())
        assertTrue(d.isSearch)
        // And it does not fall back to rendering the deck underneath.
        assertTrue(d.security.isEmpty())
        assertTrue(d.lights.isEmpty())
    }

    @Test
    fun `search matches the entity id, not only the display name`() {
        // "what was that thing called" is half of what a search box is for. The web's Devices
        // search has always matched id and area as well as name; this only matched name.
        val d = deck(
            listOf(D("Tower Fan", "Nursery", CardType.FAN, id = "fan.nursery_tower")),
            query = "nursery_tower",
        )
        assertEquals(listOf("Tower Fan"), d.searchResults.map { it.name })
    }

    @Test
    fun `search matches the area`() {
        val d = deck(
            listOf(
                D("Lamp", "Basement", CardType.LIGHT, id = "light.lamp"),
                D("Tower Fan", "Nursery", CardType.FAN, id = "fan.tower"),
            ),
            query = "basement",
        )
        assertEquals(listOf("Lamp"), d.searchResults.map { it.name })
    }
}
