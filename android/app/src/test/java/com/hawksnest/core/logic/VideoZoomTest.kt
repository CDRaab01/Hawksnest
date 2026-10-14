package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin twin of `src/lib/__tests__/videoZoom.test.ts` — same cases, same expectations. */
class VideoZoomTest {
    private val frame = FrameSize(320f, 180f)

    @Test
    fun `never zooms out past 1 to 1`() {
        assertEquals(1f, clampScale(0.25f), 0f)
        assertEquals(1f, clampScale(-3f), 0f)
    }

    @Test
    fun `caps magnification at MAX_SCALE`() {
        assertEquals(MAX_SCALE, clampScale(99f), 0f)
    }

    @Test
    fun `snaps pinch residue back to exactly 1 so the zoomed affordances turn off`() {
        assertEquals(1f, clampScale(1.004f), 0f)
        assertFalse(isZoomed(ZoomState(scale = clampScale(1.004f))))
    }

    @Test
    fun `survives NaN rather than propagating it into the transform`() {
        assertEquals(1f, clampScale(Float.NaN), 0f)
    }

    @Test
    fun `max offset is zero at 1x so an unzoomed picture is not draggable`() {
        assertEquals(0f, maxOffset(320f, 1f), 0f)
    }

    @Test
    fun `max offset is half the overflow at higher scales`() {
        assertEquals(160f, maxOffset(320f, 2f), 0f)
    }

    @Test
    fun `clamp keeps the picture covering the frame`() {
        val z = clampZoom(ZoomState(2f, 9999f, -9999f), frame)
        assertEquals(maxOffset(frame.width, 2f), z.offsetX, 0f)
        assertEquals(-maxOffset(frame.height, 2f), z.offsetY, 0f)
    }

    @Test
    fun `clamp pulls a far-panned picture back to centre when the scale drops`() {
        val zoomedRight = clampZoom(ZoomState(4f, 480f, 0f), frame)
        assertEquals(480f, zoomedRight.offsetX, 0f)
        val backOut = clampZoom(zoomedRight.copy(scale = 1.2f), frame)
        assertEquals(maxOffset(frame.width, 1.2f), backOut.offsetX, 0.001f)
        assertTrue(backOut.offsetX < 480f)
    }

    @Test
    fun `clamp recentres completely on the way back to 1x`() {
        assertEquals(NO_ZOOM, clampZoom(ZoomState(1f, 200f, 200f), frame))
    }

    @Test
    fun `pans a zoomed picture by the drag delta`() {
        val z = applyGesture(ZoomState(2f, 0f, 0f), frame, 1f, 20f, 10f, 0f, 0f)
        assertEquals(20f, z.offsetX, 0f)
        assertEquals(10f, z.offsetY, 0f)
    }

    @Test
    fun `refuses to pan at 1x`() {
        assertEquals(NO_ZOOM, applyGesture(NO_ZOOM, frame, 1f, 50f, 50f, 0f, 0f))
    }

    @Test
    fun `keeps the point under the fingers pinned while zooming`() {
        val z = applyGesture(NO_ZOOM, frame, 2f, 0f, 0f, 80f, 0f)
        assertEquals(2f, z.scale, 0f)
        assertEquals(-80f, z.offsetX, 0.001f)
    }

    @Test
    fun `stops translating once the pinch hits the max scale`() {
        val z = applyGesture(ZoomState(MAX_SCALE, 0f, 0f), frame, 2f, 0f, 0f, 80f, 0f)
        assertEquals(MAX_SCALE, z.scale, 0f)
        assertEquals(0f, z.offsetX, 0f)
    }

    @Test
    fun `clamps a zoom-out so the picture cannot be left off-centre`() {
        assertEquals(NO_ZOOM, applyGesture(ZoomState(4f, 480f, 270f), frame, 0.1f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun `treats a zero or negative scale change as no change`() {
        assertEquals(2f, applyGesture(ZoomState(2f), frame, 0f, 0f, 0f, 0f, 0f).scale, 0f)
        assertEquals(2f, applyGesture(ZoomState(2f), frame, -1f, 0f, 0f, 0f, 0f).scale, 0f)
    }

    @Test
    fun `lets the page scroll when the picture is not zoomed`() {
        assertFalse(shouldCaptureDrag(NO_ZOOM))
    }

    @Test
    fun `captures drags once zoomed so panning works`() {
        assertTrue(shouldCaptureDrag(ZoomState(2f, 0f, 0f)))
    }

    // --- a picture smaller than its stage (the player's panorama case) -------------------------

    // A 3.5:1 panorama fitted into a 1000x1300 stage: 1000x285.
    private val stage = FrameSize(1000f, 1300f)
    private val panorama = fittedContent(stage, 3.5f)

    @Test
    fun `fitting letterboxes into the stage on the right axis`() {
        assertEquals(1000f, panorama.width, 0.01f)
        assertEquals(285.71f, panorama.height, 0.01f)
        val wideStage = fittedContent(FrameSize(2000f, 900f), 16f / 9f)
        assertEquals(1600f, wideStage.width, 0.01f)
        assertEquals(900f, wideStage.height, 0.01f)
        // A bad aspect falls back to the stage itself (the old picture-fills-frame behaviour).
        assertEquals(stage, fittedContent(stage, 0f))
    }

    @Test
    fun `a zoomed panorama grows past its strip and stays centred until it is taller than the stage`() {
        val z = applyGesture(NO_ZOOM, stage, 2f, 0f, 500f, 0f, 0f, panorama)
        // 2x: 571 px tall, still inside the 1300 px stage, so no vertical pan at all.
        assertEquals(0f, z.offsetY, 0.01f)
        // ...but 2000 px wide, so it pans sideways by up to half the overflow.
        val panned = applyGesture(z, stage, 1f, 900f, 0f, 0f, 0f, panorama)
        assertEquals(500f, panned.offsetX, 0.01f)
    }

    @Test
    fun `the zoom ceiling rises so a panorama can be filled and then looked into`() {
        val cover = coverScale(stage, panorama)
        assertEquals(4.55f, cover, 0.01f)
        assertEquals(cover * 2f, maxScaleFor(stage, panorama), 0.01f)
        // A picture that fills its frame keeps the original 4x ceiling.
        assertEquals(MAX_SCALE, maxScaleFor(frame), 0.0f)
    }

    @Test
    fun `double tap fills the stage around the tap, and again resets`() {
        val filled = doubleTapZoom(NO_ZOOM, stage, panorama, tapX = 300f, tapY = 0f)
        assertEquals(coverScale(stage, panorama), filled.scale, 0.01f)
        // The tapped side of the panorama is brought into view (offset opposite the tap).
        assertTrue(filled.offsetX < 0f)
        assertEquals(NO_ZOOM, doubleTapZoom(filled, stage, panorama, 0f, 0f))
    }

    @Test
    fun `where the picture already fills the stage, double tap doubles`() {
        assertEquals(2f, doubleTapZoom(NO_ZOOM, frame, frame, 0f, 0f).scale, 0.0f)
    }

    @Test
    fun `same stage and picture keep the original web-parity behaviour`() {
        val a = applyGesture(NO_ZOOM, frame, 2.5f, 40f, -30f, 10f, 20f)
        val b = applyGesture(NO_ZOOM, frame, 2.5f, 40f, -30f, 10f, 20f, frame)
        assertEquals(a, b)
        assertEquals(maxOffset(320f, 3f), maxOffset(320f, 3f, 320f), 0.0f)
    }
}
