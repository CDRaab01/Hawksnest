package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ports `clipSeek.ts` behavior (see `src/lib/__tests__/clipSeek.test.ts`). */
class ClipSeekTest {

    private val T0 = 1_700_000_000_000L

    private fun clip(id: String, startMs: Long, endMs: Long? = null) = CameraEvent(
        id = id,
        camera = "gate",
        label = "motion",
        startMs = startMs,
        endMs = endMs,
        hasClip = true,
        hasSnapshot = false,
        thumbnailUrl = null,
        snapshotUrl = null,
    )

    @Test
    fun `span end uses the real endMs when the event has one`() {
        assertEquals(T0 + 90_000, clipSpanEndMs(clip("a", T0, T0 + 90_000), null, null))
    }

    @Test
    fun `span end uses the loaded media duration for the loaded open-ended clip`() {
        assertEquals(T0 + 62_000, clipSpanEndMs(clip("a", T0), "a", 62_000))
    }

    @Test
    fun `span end ignores the loaded duration for a different clip and non-positive durations`() {
        assertEquals(T0 + ASSUMED_CLIP_SPAN_MS, clipSpanEndMs(clip("a", T0), "b", 62_000))
        assertEquals(T0 + ASSUMED_CLIP_SPAN_MS, clipSpanEndMs(clip("a", T0), "a", 0))
    }

    @Test
    fun `span end assumes a short span when nothing better is known`() {
        assertEquals(T0 + ASSUMED_CLIP_SPAN_MS, clipSpanEndMs(clip("a", T0), null, null))
    }

    private val events = listOf(clip("a", T0, T0 + 60_000), clip("b", T0 + 120_000))

    @Test
    fun `containment finds the clip whose span contains the time, boundaries inclusive`() {
        assertEquals("a", clipContaining(events, T0, null, null)?.id)
        assertEquals("a", clipContaining(events, T0 + 60_000, null, null)?.id)
        assertEquals("b", clipContaining(events, T0 + 130_000, null, null)?.id)
    }

    @Test
    fun `containment returns null in a gap between clips`() {
        assertNull(clipContaining(events, T0 + 90_000, null, null))
        assertNull(clipContaining(events, T0 + 120_000 + ASSUMED_CLIP_SPAN_MS + 1, null, null))
    }

    @Test
    fun `containment extends once the loaded clip's duration is known`() {
        val t = T0 + 120_000 + 45_000 // outside b's assumed span…
        assertNull(clipContaining(events, t, null, null))
        assertEquals("b", clipContaining(events, t, "b", 50_000)?.id) // …inside its real one
    }

    @Test
    fun `containment prefers the latest-starting clip on overlap`() {
        val overlapping = listOf(clip("a", T0, T0 + 180_000), clip("b", T0 + 120_000))
        assertEquals("b", clipContaining(overlapping, T0 + 125_000, null, null)?.id)
    }

    @Test
    fun `containment returns null on an empty list`() {
        assertNull(clipContaining(emptyList(), T0, null, null))
    }

    @Test
    fun `offset maps a timeline time to ms into the clip, clamped at zero`() {
        assertEquals(12_500, offsetInClipMs(clip("a", T0), T0 + 12_500))
        assertEquals(0, offsetInClipMs(clip("a", T0), T0 - 5_000))
    }
}
