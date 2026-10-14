package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `mediaAspect.test.ts` 1:1, plus `wallRows` (which has no web twin — see MediaAspect.kt). */
class MediaAspectTest {

    private fun assertClose(expected: Float, actual: Float?, tolerance: Float = 0.01f) {
        assertTrue(actual != null && kotlin.math.abs(actual - expected) <= tolerance, "was $actual")
    }

    @Test
    fun `aspectFromDimensions returns width over height for real dimensions`() {
        assertClose(3.56f, aspectFromDimensions(1536, 432)) // dual-lens panorama
        assertClose(1.33f, aspectFromDimensions(640, 480)) // 4:3 doorbell
        assertClose(1.78f, aspectFromDimensions(1920, 1080)) // 16:9
    }

    @Test
    fun `aspectFromDimensions returns null when either dimension is unknown`() {
        // A renderer reports 0x0 before its first frame — 0 must never become an aspect ratio
        // (it would collapse the frame to zero height).
        assertNull(aspectFromDimensions(0, 0))
        assertNull(aspectFromDimensions(640, 0))
        assertNull(aspectFromDimensions(0, 480))
        assertNull(aspectFromDimensions(-1, 100))
    }

    @Test
    fun `isWideAspect classifies dual-lens panoramas as wide`() {
        assertTrue(isWideAspect(3.56f)) // 1536x432 stitched Duo
        assertTrue(isWideAspect(WIDE_ASPECT_THRESHOLD)) // boundary is inclusive
    }

    @Test
    fun `isWideAspect classifies normal single-lens cameras as not wide`() {
        assertFalse(isWideAspect(DEFAULT_ASPECT)) // 16:9
        assertFalse(isWideAspect(4f / 3f)) // doorbell
        assertFalse(isWideAspect(1.99f)) // just under the threshold
    }

    // With no panorama in the list this must behave exactly like the `chunked(2)` it replaces —
    // the regression guard for every wall that has no wide camera.
    @Test
    fun `wallRows pairs cameras up when none is wide`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c")),
            wallRows(listOf("a", "b", "c")) { false },
        )
        assertEquals(emptyList(), wallRows(emptyList<String>()) { false })
    }

    @Test
    fun `wallRows gives a wide camera its own full-width row`() {
        assertEquals(
            listOf(listOf("wide"), listOf("a", "b")),
            wallRows(listOf("wide", "a", "b")) { it == "wide" },
        )
    }

    // A wide tile displaces its would-be partner into the next row, exactly as CSS grid
    // auto-placement does on the web wall.
    @Test
    fun `wallRows leaves a short row where a wide tile displaces its partner`() {
        assertEquals(
            listOf(listOf("a"), listOf("wide"), listOf("b")),
            wallRows(listOf("a", "wide", "b")) { it == "wide" },
        )
    }
}
