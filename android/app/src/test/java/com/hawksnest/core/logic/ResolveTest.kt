package com.hawksnest.core.logic

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QuestionMark
import com.hawksnest.config.overrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Ported from `src/lib/__tests__/resolve.test.ts`. */
class ResolveTest {

    @Test
    fun `override wins over friendly_name and id`() {
        val contact = entity("binary_sensor.front_door_current_status", "Lock Current status")
        assertEquals("Front Door", resolveName(contact, overrides))
    }

    @Test
    fun `falls back to friendly_name when no override`() {
        val e = entity("sensor.kitchen_temp", "Kitchen Temperature")
        assertEquals("Kitchen Temperature", resolveName(e, overrides))
    }

    @Test
    fun `prettifies the entity_id as last resort`() {
        assertEquals("Garage Work Light", resolveName(entity("switch.garage_work_light")))
    }

    @Test
    fun `ignores blank friendly_name`() {
        assertEquals("Hall", resolveName(entity("light.hall", "   ")))
    }

    @Test
    fun `prettifyEntityId strips the domain and title-cases`() {
        assertEquals(
            "Front Door Current Status",
            prettifyEntityId("binary_sensor.front_door_current_status"),
        )
    }

    @Test
    fun `resolveIcon uses the override icon when present`() {
        val e = entity("lock.front_door_lock", "Lock")
        assertSame(Icons.Filled.Lock, resolveIcon(e, overrides))
    }

    @Test
    fun `resolveIcon falls back to a neutral icon for an unknown domain`() {
        val e = entity("totally_made_up.thing")
        assertSame(Icons.Filled.QuestionMark, resolveIcon(e))
    }
}
