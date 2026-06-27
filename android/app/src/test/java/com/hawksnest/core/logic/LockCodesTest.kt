package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from `src/lib/__tests__/lockCodes.test.ts`. */
class LockCodesTest {

    @Test
    fun `validates 4 to 8 digit codes`() {
        assertTrue(isValidUserCode("1234"))
        assertTrue(isValidUserCode("12345678"))
        assertFalse(isValidUserCode("123"))
        assertFalse(isValidUserCode("123456789"))
        assertFalse(isValidUserCode("12a4"))
        assertFalse(isValidUserCode(""))
    }

    @Test
    fun `guest automation ids are stable and recognizable`() {
        val id = guestAutomationId("lock.front_door_lock", 3)
        assertEquals("hawksnest_guest_lock_front_door_lock_slot3", id)
        assertTrue(isGuestAutomation(id))
        assertFalse(isGuestAutomation("1700000000000"))
        assertEquals(3, guestSlotFromId(id))
        assertNull(guestSlotFromId("1700000000000"))
    }

    @Test
    fun `lock entity detection`() {
        assertTrue(isLockEntity("lock.front_door_lock"))
        assertFalse(isLockEntity("light.basement"))
    }

    @Test
    fun `guest expiry automation is null for invalid datetime`() {
        assertNull(buildGuestExpiryAutomation("lock.front_door_lock", 3, "Sitter", "nope"))
    }

    @Test
    fun `guest expiry automation has the expected structure`() {
        val cfg = buildGuestExpiryAutomation("lock.front_door_lock", 4, "Sitter", "2026-07-01T14:30")!!
        assertEquals("hawksnest_guest_lock_front_door_lock_slot4", cfg["id"]!!.jsonPrimitive.content)

        val trigger = (cfg["trigger"] as JsonArray)[0].jsonObject
        assertEquals("time", trigger["platform"]!!.jsonPrimitive.content)
        assertEquals("14:30:00", trigger["at"]!!.jsonPrimitive.content)

        val condition = (cfg["condition"] as JsonArray)[0].jsonObject
        assertEquals(
            "{{ as_timestamp(now()) >= as_timestamp('2026-07-01 14:30:00') }}",
            condition["value_template"]!!.jsonPrimitive.content,
        )

        val actions = cfg["action"] as JsonArray
        val clear = actions[0].jsonObject
        assertEquals("zwave_js.clear_lock_usercode", clear["service"]!!.jsonPrimitive.content)
        assertEquals(4, (clear["data"] as JsonObject)["code_slot"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "lock.front_door_lock",
            (clear["target"] as JsonObject)["entity_id"]!!.jsonPrimitive.content,
        )
        assertEquals("automation.turn_off", actions[1].jsonObject["service"]!!.jsonPrimitive.content)
    }
}
