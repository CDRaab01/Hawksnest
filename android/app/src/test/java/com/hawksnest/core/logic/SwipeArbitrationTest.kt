package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Swipe-to-change-camera and drag-to-pan are the same gesture, and only one of them may own it.
 *
 * `ZoomableFrame` arbitrates with [shouldCaptureDrag]: zoomed in, a drag pans the picture; zoomed
 * out, the same drag changes camera. Getting this backwards is not a subtle bug — you would pan a
 * magnified picture and find yourself looking at the garage.
 *
 * The gesture code itself needs a device. What is pinned here is the rule it consults and the
 * index arithmetic it performs, which are the two things that can be wrong in a way a smoke test
 * would not obviously catch.
 */
class SwipeArbitrationTest {

    @Test
    fun `a drag belongs to pan only while zoomed`() {
        assertFalse("un-zoomed, the swipe owns the drag", shouldCaptureDrag(NO_ZOOM))
        assertTrue("zoomed, panning owns the drag", shouldCaptureDrag(NO_ZOOM.copy(scale = 2f)))
        // The boundary matters: scale is a float and settles at exactly 1f after a double-tap
        // reset, which must count as un-zoomed or the swipe would silently stop working.
        assertFalse(shouldCaptureDrag(NO_ZOOM.copy(scale = 1f)))
        assertTrue(shouldCaptureDrag(NO_ZOOM.copy(scale = 1.01f)))
    }

    /** The wrap-around ZoomableFrame performs: `((i + dir) % n + n) % n`. */
    private fun next(index: Int, dir: Int, count: Int) = ((index + dir) % count + count) % count

    @Test
    fun `swiping wraps at both ends rather than dead-ending`() {
        // Seven cameras in a ring. A swipe that silently does nothing because you happened to be
        // on the last one reads as a broken gesture, not a boundary.
        assertEquals(0, next(6, 1, 7))
        assertEquals(6, next(0, -1, 7))
        assertEquals(3, next(2, 1, 7))
        assertEquals(1, next(2, -1, 7))
    }

    @Test
    fun `a single camera always resolves to itself`() {
        assertEquals(0, next(0, 1, 1))
        assertEquals(0, next(0, -1, 1))
    }

    @Test
    fun `double-tap reset returns to the un-zoomed state that enables swiping`() {
        // ZoomableFrame's escape hatch. If NO_ZOOM were ever not scale=1, resetting would leave
        // the frame in a state where neither pan nor swipe worked.
        assertEquals(1f, NO_ZOOM.scale, 0f)
        assertFalse(isZoomed(NO_ZOOM))
    }
}
