package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `doorbellControls.test.ts` 1:1. */
class DoorbellControlsTest {

    private val slug = "front_door_front_door_reolink"

    /** The real entity set the Reolink integration produced for the D340W doorbell. */
    private val live = listOf(
        "camera.front_door_reolink",
        "binary_sensor.front_door_reolink_visitor",
        "binary_sensor.front_door_reolink_motion",
        "number.${slug}_doorbell_volume",
        "number.${slug}_volume",
        "switch.${slug}_doorbell_button_sound",
        "select.${slug}_auto_quick_reply_message",
        "select.${slug}_play_quick_reply_message",
        "siren.${slug}_siren",
    )

    @Test
    fun `bridges the doubled Reolink slug to the Frigate camera base`() {
        // The integration names entities `<host device>_<channel device>_<entity>`, so the
        // doorbell's slug is `front_door_front_door_reolink` while its camera is
        // `camera.front_door_reolink`. Deriving ids from the base would find nothing.
        val c = resolveDoorbellControls("front_door_reolink", live)
        assertNotNull(c)
        assertEquals(slug, c.slug)
        assertEquals("number.${slug}_doorbell_volume", c.volume)
        assertEquals("switch.${slug}_doorbell_button_sound", c.buttonSound)
        assertEquals("select.${slug}_auto_quick_reply_message", c.autoReply)
        assertEquals("select.${slug}_play_quick_reply_message", c.playReply)
    }

    @Test
    fun `resolves the siren from the siren domain, not ring-mqtt's switch`() {
        // `LogicalCamera.sirenSwitchId` only ever looks for `switch.<base>_siren`. The Reolink
        // siren is a different domain with a different service, so it must come from here.
        val c = resolveDoorbellControls("front_door_reolink", live)
        assertEquals("siren.${slug}_siren", c!!.siren)
        assertTrue(c.siren!!.startsWith("siren."))
    }

    @Test
    fun `returns null for a wall camera, so no doorbell chrome appears on it`() {
        assertNull(
            resolveDoorbellControls(
                "kitchen",
                listOf(
                    "camera.kitchen",
                    "binary_sensor.kitchen_motion",
                    "number.kitchen_volume",
                    "siren.kitchen_siren",
                ),
            ),
        )
    }

    @Test
    fun `prefers an exact slug over a fuzzy one`() {
        val ids = listOf(
            "number.front_door_reolink_doorbell_volume",
            "number.${slug}_doorbell_volume",
        )
        assertEquals("front_door_reolink", resolveDoorbellControls("front_door_reolink", ids)!!.slug)
    }

    @Test
    fun `returns null when two candidates are equally plausible`() {
        // Guessing would point the volume slider at the wrong doorbell.
        val ids = listOf(
            "number.a_front_door_doorbell_volume",
            "number.b_front_door_doorbell_volume",
        )
        assertNull(resolveDoorbellControls("front_door", ids))
    }

    @Test
    fun `honours an explicit alias`() {
        val ids = listOf("number.lobby_unit_doorbell_volume")
        assertEquals(
            "lobby_unit",
            resolveDoorbellControls("porch", ids, mapOf("porch" to "lobby_unit"))!!.slug,
        )
    }

    @Test
    fun `reports optional controls as null rather than inventing ids`() {
        val c = resolveDoorbellControls("porch", listOf("number.porch_doorbell_volume"))!!
        assertEquals("porch", c.slug)
        assertEquals("number.porch_doorbell_volume", c.volume)
        assertNull(c.buttonSound)
        assertNull(c.autoReply)
        assertNull(c.playReply)
        assertNull(c.siren)
    }

    @Test
    fun `does not mistake a plain volume entity for a doorbell`() {
        // Every Reolink has `_volume`; only doorbells have `_doorbell_volume`.
        assertNull(resolveDoorbellControls("garage", listOf("number.garage_volume")))
    }
}
