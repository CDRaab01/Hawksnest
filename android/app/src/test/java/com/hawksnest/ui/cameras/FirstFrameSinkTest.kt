package com.hawksnest.ui.cameras

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The regression these guard is "the live view is stuck on Connecting…": the overlay is re-armed
 * for every new stream, so whatever clears it must fire once per SESSION. The renderer's own
 * `onFirstFrameRendered` fires once per `init()` and the renderer outlives every stream, which is
 * exactly why the second stream on a player could never clear it.
 */
class FirstFrameSinkTest {

    @Test
    fun `reports the first frame`() {
        var fired = 0
        val sink = FirstFrameSink { fired++ }

        sink.markFrame()

        assertEquals(1, fired)
    }

    @Test
    fun `reports only once, however many frames arrive`() {
        var fired = 0
        val sink = FirstFrameSink { fired++ }

        repeat(50) { sink.markFrame() }

        assertEquals(1, fired, "the overlay should be cleared once per session, not per frame")
    }

    /**
     * The bug, stated as a test: a second stream on the same player gets its own sink, so it clears
     * the overlay on its own first frame. Before the fix the two streams shared one latch — the
     * renderer's — and the second could never report.
     */
    @Test
    fun `a second session reports its own first frame`() {
        var fired = 0
        val first = FirstFrameSink { fired++ }
        first.markFrame()

        // Quality toggle / camera switch: DisposableEffect(src) builds a NEW session.
        val second = FirstFrameSink { fired++ }
        second.markFrame()

        assertEquals(2, fired, "each session must clear the overlay it re-armed")
    }

    @Test
    fun `concurrent frames still report exactly once`() {
        var fired = 0
        val sink = FirstFrameSink { synchronized(this) { fired++ } }

        // libwebrtc delivers on its own threads; the latch must be atomic.
        val threads = List(8) { Thread { repeat(100) { sink.markFrame() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, fired)
    }
}
