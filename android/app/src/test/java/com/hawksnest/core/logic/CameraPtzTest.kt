package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ports `cameraPtz.ts` behavior (see `src/lib/__tests__/cameraPtz.test.ts`).
 *
 * Fixtures mirror the REAL entity set measured on the cluster 2026-07-30 — two
 * E1 Zooms (full controls) and an E1 Pro (pan/tilt only), with the stairway's
 * Reolink device deliberately named `stairway` while its Frigate camera is
 * `camera.first_floor_stairway`.
 */
class CameraPtzTest {

    private fun ptzButtons(slug: String) =
        listOf("up", "down", "left", "right", "stop").map { "button.${slug}_ptz_$it" }

    private val live = buildList {
        addAll(ptzButtons("big_room"))
        addAll(ptzButtons("stairway"))
        addAll(ptzButtons("kitchen"))
        addAll(
            listOf(
                "number.big_room_zoom",
                "number.big_room_focus",
                "switch.big_room_auto_focus",
                "number.stairway_zoom",
                "number.stairway_focus",
                "switch.stairway_auto_focus",
                // Noise that must never be mistaken for PTZ.
                "camera.big_room",
                "camera.first_floor_stairway",
                "select.master_bedroom_preset",
                "sensor.kitchen_ptz_pan_position",
            ),
        )
    }

    @Test
    fun `resolves an E1 Zoom's full control set on an exact name match`() {
        val ptz = resolvePtz("big_room", live)!!
        assertEquals("big_room", ptz.slug)
        assertEquals("button.big_room_ptz_up", ptz.up)
        assertEquals("button.big_room_ptz_stop", ptz.stop)
        assertEquals("number.big_room_zoom", ptz.zoom)
        assertEquals("number.big_room_focus", ptz.focus)
        assertEquals("switch.big_room_auto_focus", ptz.autofocus)
    }

    // The bug this module exists to prevent: ids derived from the camera base
    // would find nothing here, and PTZ would vanish with no error.
    @Test
    fun `bridges the stairway alias - Reolink stairway vs camera first_floor_stairway`() {
        val ptz = resolvePtz("first_floor_stairway", live)!!
        assertEquals("stairway", ptz.slug)
        assertEquals("button.stairway_ptz_up", ptz.up)
        assertEquals("number.stairway_zoom", ptz.zoom)
    }

    @Test
    fun `gives the E1 Pro a pad but no zoom, focus or autofocus`() {
        val ptz = resolvePtz("kitchen", live)!!
        assertEquals("button.kitchen_ptz_left", ptz.left)
        assertNull(ptz.zoom)
        assertNull(ptz.focus)
        assertNull(ptz.autofocus)
    }

    @Test
    fun `has no preset until one is saved on the camera`() {
        assertNull(resolvePtz("big_room", live)!!.preset)
        assertEquals(
            "select.big_room_ptz_preset",
            resolvePtz("big_room", live + "select.big_room_ptz_preset")!!.preset,
        )
    }

    @Test
    fun `null for a camera with no PTZ entities, and for a system with none`() {
        assertNull(resolvePtz("front_door", live))
        assertNull(resolvePtz("big_room", listOf("camera.big_room", "switch.big_room_siren")))
    }

    @Test
    fun `does not match a leading fragment or a non-boundary fragment`() {
        assertNull(resolvePtz("big", live))
        assertNull(resolvePtz("first_floor", live))
        assertNull(resolvePtz("oom", live))
        assertNull(resolvePtz("way", live))
    }

    // The documented residual gap of the trailing rule, asserted so it stays a
    // known, pinnable behaviour rather than a surprise.
    @Test
    fun `does match a bare trailing segment - pin it with an alias if that is wrong`() {
        assertEquals("big_room", resolvePtz("room", live)!!.slug)
        assertEquals("kitchen", resolvePtz("room", live, mapOf("room" to "kitchen"))!!.slug)
    }

    @Test
    fun `fails closed when two candidates are equally plausible`() {
        val ambiguous = ptzButtons("yard") + ptzButtons("side_yard")
        assertNull(resolvePtz("north_side_yard", ambiguous))
        assertEquals(
            "side_yard",
            resolvePtz("north_side_yard", ambiguous, mapOf("north_side_yard" to "side_yard"))!!.slug,
        )
    }

    @Test
    fun `prefers an exact match over a fuzzy one`() {
        val both = ptzButtons("stairway") + ptzButtons("first_floor_stairway")
        assertEquals("first_floor_stairway", resolvePtz("first_floor_stairway", both)!!.slug)
    }

    @Test
    fun `ignores an override naming a camera that has no PTZ`() {
        val ptz = resolvePtz("first_floor_stairway", live, mapOf("first_floor_stairway" to "nope"))
        assertEquals("stairway", ptz!!.slug)
    }

    // A pad that can move but not stop would leave the camera panning.
    @Test
    fun `fails closed when stop or a direction is missing`() {
        assertNull(resolvePtz("big_room", live - "button.big_room_ptz_stop"))
        assertNull(resolvePtz("big_room", live - "button.big_room_ptz_left"))
    }
}
