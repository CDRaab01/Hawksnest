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
}
