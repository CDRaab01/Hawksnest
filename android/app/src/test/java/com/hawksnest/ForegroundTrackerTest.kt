package com.hawksnest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the started-activity count behind the HA socket's foreground gate. The edges matter more
 * than the count: a missed `false` leaves the socket streaming in a pocket (the battery bug this
 * exists to fix), and a spurious one drops it under a visible screen.
 */
class ForegroundTrackerTest {

    private val edges = mutableListOf<Boolean>()
    private val tracker = ForegroundTracker { edges += it }

    @Test
    fun `first start and last stop are the only edges`() {
        tracker.activityStarted()
        tracker.activityStarted() // MainActivity -> WidgetConfigActivity: second start, no edge
        tracker.activityStopped()
        assertEquals(listOf(true), edges)
        tracker.activityStopped()
        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `a rotation-style stop then start reports both edges for the grace period to absorb`() {
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.activityStarted()
        assertEquals(listOf(true, false, true), edges)
    }

    @Test
    fun `an unmatched stop neither reports nor drives the count negative`() {
        tracker.activityStopped()
        assertEquals(emptyList<Boolean>(), edges)
        // Had the count gone to -1, this start would land on 0 and never report.
        tracker.activityStarted()
        assertEquals(listOf(true), edges)
    }
}
