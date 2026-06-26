package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the safety-critical Home security read-out (extracted from `HomeViewModel` into the
 * pure [securityReadout] so it can be tested without Hilt / a HA connection).
 */
class SecurityTest {

    private fun lock(id: String, state: String, name: String? = null) =
        HassEntity(
            entityId = id,
            state = state,
            attributes = buildJsonObject { if (name != null) put("friendly_name", name) },
        )

    private fun sensor(id: String, deviceClass: String, state: String, name: String? = null) =
        HassEntity(
            entityId = id,
            state = state,
            attributes = buildJsonObject {
                put("device_class", deviceClass)
                if (name != null) put("friendly_name", name)
            },
        )

    @Test
    fun allClear_whenEverythingLockedAndClosed() {
        val r = securityReadout(
            listOf(
                lock("lock.front_door_lock", "locked", "Front Door"),
                sensor("binary_sensor.back_door", "door", "off", "Back Door"),
            ),
        )
        assertTrue(r.allClear)
        assertEquals("All doors locked", r.summary)
        assertNull(r.offlineLabel)
    }

    @Test
    fun lockingState_countsAsSecure() {
        // A lock mid-transition (locking) must not read as "unlocked" — avoids a false alarm banner.
        val r = securityReadout(listOf(lock("lock.front_door_lock", "locking", "Front Door")))
        assertTrue(r.allClear)
        assertEquals("All doors locked", r.summary)
    }

    @Test
    fun unlockedLock_isFlagged() {
        val r = securityReadout(listOf(lock("lock.front_door_lock", "unlocked", "Front Door")))
        assertFalse(r.allClear)
        assertEquals("Front Door unlocked", r.summary)
    }

    @Test
    fun openDoorAndUnlockedLock_areJoined() {
        val r = securityReadout(
            listOf(
                lock("lock.back_door_lock", "unlocked", "Back Door"),
                sensor("binary_sensor.garage", "garage_door", "on", "Garage"),
            ),
        )
        assertFalse(r.allClear)
        assertEquals("Back Door unlocked · Garage open", r.summary)
    }

    @Test
    fun nonDoorBinarySensor_doesNotAffectSecurity() {
        // A motion sensor being "on" is not an open door.
        val r = securityReadout(listOf(sensor("binary_sensor.hall_motion", "motion", "on", "Hall")))
        assertTrue(r.allClear)
    }

    @Test
    fun lifeSafety_surfacesTriggeredAndCountsMonitored() {
        val r = securityReadout(
            listOf(
                sensor("binary_sensor.kitchen_smoke", "smoke", "on", "Kitchen Smoke"),
                sensor("binary_sensor.basement_leak", "moisture", "off", "Basement Leak"),
            ),
        )
        assertEquals(listOf("Kitchen Smoke"), r.lifeSafetyAlerts)
        assertEquals(2, r.lifeSafetyMonitored)
    }

    @Test
    fun offlineLabel_singleAndMultiple() {
        val one = securityReadout(listOf(lock("lock.front_door_lock", "unavailable", "Front Door")))
        assertEquals("Front Door is offline", one.offlineLabel)

        val many = securityReadout(
            listOf(
                lock("lock.front_door_lock", "unavailable", "Front Door"),
                lock("lock.back_door_lock", "unavailable", "Back Door"),
            ),
        )
        assertEquals("Front Door +1 more offline", many.offlineLabel)
    }

    @Test
    fun overrides_renameEntitiesInReadout() {
        val overrides: OverrideMap = mapOf("lock.x" to EntityOverride(name = "Side Gate"))
        val r = securityReadout(listOf(lock("lock.x", "unlocked")), overrides)
        assertEquals("Side Gate unlocked", r.summary)
    }
}
