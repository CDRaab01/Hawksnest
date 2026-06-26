package com.hawksnest.push

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the severity → channel routing (life-safety is its own always-alerting tier). */
class PushChannelsTest {

    @Test
    fun `life-safety words route to the life-safety channel`() {
        listOf("life_safety", "smoke", "co", "carbon_monoxide", "gas", "leak", "water").forEach {
            assertEquals(PushChannels.LIFE_SAFETY, PushChannels.channelFor(it))
        }
    }

    @Test
    fun `security words route to the security channel`() {
        listOf("intrusion", "alarm", "triggered", "motion", "door").forEach {
            assertEquals(PushChannels.SECURITY, PushChannels.channelFor(it))
        }
    }

    @Test
    fun `unknown or missing severity falls back to info`() {
        assertEquals(PushChannels.INFO, PushChannels.channelFor(null))
        assertEquals(PushChannels.INFO, PushChannels.channelFor(""))
        assertEquals(PushChannels.INFO, PushChannels.channelFor("battery_low"))
    }

    @Test
    fun `routing is case-insensitive and trims`() {
        assertEquals(PushChannels.LIFE_SAFETY, PushChannels.channelFor("  SMOKE "))
        assertEquals(PushChannels.SECURITY, PushChannels.channelFor("Intrusion"))
    }
}
