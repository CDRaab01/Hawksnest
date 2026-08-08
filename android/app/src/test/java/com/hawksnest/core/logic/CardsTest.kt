package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ported from `src/lib/__tests__/cards.test.ts`. */
class CardsTest {

    @Test
    fun `maps first-class domains to their cards`() {
        assertEquals(CardType.LOCK, domainToCard("lock.front_door_lock"))
        assertEquals(CardType.LIGHT, domainToCard("light.basement"))
        assertEquals(CardType.CAMERA, domainToCard("camera.front_door"))
        assertEquals(CardType.CAMERA, domainToCard("image.snapshot"))
        assertEquals(CardType.BINARY_SENSOR, domainToCard("binary_sensor.front_door"))
        assertEquals(CardType.ALARM, domainToCard("alarm_control_panel.home"))
        assertEquals(CardType.COVER, domainToCard("cover.living_room_blinds"))
        assertEquals(CardType.CLIMATE, domainToCard("climate.living_room"))
        assertEquals(CardType.MEDIA_PLAYER, domainToCard("media_player.living_room"))
        assertEquals(CardType.FAN, domainToCard("fan.bedroom"))
    }

    @Test
    fun `falls back to GenericCard for unmapped and unknown domains`() {
        assertEquals(CardType.GENERIC, domainToCard("weather.home"))
        assertEquals(CardType.GENERIC, domainToCard("totally_made_up.thing"))
    }

    @Test
    fun `Devices hub excludes non-device and plumbing domains`() {
        // Non-devices with their own surfaces / infrastructure entities.
        for (domain in listOf("automation", "script", "scene", "person", "zone", "sun")) {
            assertEquals(true, domain in NON_DEVICE_DOMAINS, "expected $domain excluded")
        }
        // Device plumbing (PTZ buttons, scene-controller event streams, AI-snapshot images) —
        // trimmed 2026-08-07; reachable via entity-detail diagnostics instead.
        for (domain in listOf("button", "event", "image")) {
            assertEquals(true, domain in NON_DEVICE_DOMAINS, "expected $domain excluded")
        }
        // The domains the tab exists for must never creep into the exclusion set.
        for (domain in listOf("lock", "light", "switch", "camera", "sensor", "binary_sensor")) {
            assertEquals(false, domain in NON_DEVICE_DOMAINS, "expected $domain included")
        }
    }
}
