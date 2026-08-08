package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class PinsTest {

    private val seed = listOf("lock.front", "lock.back", "alarm.home")

    @Test
    fun `null stored means the seed, verbatim and in order`() {
        assertEquals(seed, effectivePins(null, seed))
    }

    @Test
    fun `a stored list wins over the seed, even when empty`() {
        assertEquals(emptyList<String>(), effectivePins(emptyList(), seed))
        assertEquals(listOf("light.x"), effectivePins(listOf("light.x"), seed))
    }

    @Test
    fun `toggle adds at the end and removes in place`() {
        assertEquals(seed + "light.x", togglePin(seed, "light.x"))
        assertEquals(listOf("lock.front", "alarm.home"), togglePin(seed, "lock.back"))
    }

    @Test
    fun `move clamps at the ends and ignores unknown ids`() {
        assertEquals(listOf("lock.back", "lock.front", "alarm.home"), movePin(seed, "lock.back", -1))
        assertEquals(seed, movePin(seed, "lock.front", -1)) // already first
        assertEquals(seed, movePin(seed, "alarm.home", 1)) // already last
        assertEquals(seed, movePin(seed, "light.unknown", 1))
    }
}
