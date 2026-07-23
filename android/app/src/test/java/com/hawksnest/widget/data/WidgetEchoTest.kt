package com.hawksnest.widget.data

import com.hawksnest.core.logic.WIDGET_ECHO_TIMEOUT_MS
import com.hawksnest.core.logic.WidgetKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The widget's echo wait, driven by a fake clock so the thirty-second timeout is a millisecond
 * test. Each case is one the real hardware can produce: the lock turns, the lock jams, the lock
 * says nothing at all.
 */
class WidgetEchoTest {

    /** A clock that only advances when the code under test sleeps. */
    private class FakeClock {
        var now = 1_000_000L
            private set
        val slept = mutableListOf<Long>()

        fun echo() = WidgetEcho(nowMs = { now }, sleep = { ms -> slept += ms; now += ms })
    }

    @Test
    fun `settles as soon as the lock reports a new resting state`() = runTest {
        val clock = FakeClock()
        val states = ArrayDeque(listOf("locked", "unlocking", "unlocked"))
        val outcome = clock.echo().awaitSettled(WidgetKind.LOCK, before = "locked") {
            states.removeFirstOrNull()
        }
        assertEquals(EchoOutcome.Settled("unlocked"), outcome)
    }

    @Test
    fun `keeps polling through a transitional state rather than stopping there`() = runTest {
        // In-app, `locking` is a fine place to stop — the socket carries the rest of the story.
        // A widget that stopped there would sit on "Locking…" until something else redrew it.
        val clock = FakeClock()
        val states = ArrayDeque(listOf("locking", "locking", "locked"))
        val outcome = clock.echo().awaitSettled(WidgetKind.LOCK, before = "unlocked") {
            states.removeFirstOrNull()
        }
        assertEquals(EchoOutcome.Settled("locked"), outcome)
        assertEquals(3, clock.slept.size)
    }

    @Test
    fun `a jam ends the wait`() = runTest {
        val clock = FakeClock()
        val outcome = clock.echo().awaitSettled(WidgetKind.LOCK, before = "unlocked") { "jammed" }
        assertEquals(EchoOutcome.Settled("jammed"), outcome)
    }

    @Test
    fun `gives up after the timeout and reports the last thing it saw`() = runTest {
        val clock = FakeClock()
        val outcome = clock.echo().awaitSettled(WidgetKind.LOCK, before = "locked") { "locked" }
        assertEquals(EchoOutcome.GaveUp("locked"), outcome)
        // Bounded by the same allowance ControlGate gives an in-app control.
        assertTrue(clock.slept.sum() >= WIDGET_ECHO_TIMEOUT_MS)
    }

    @Test
    fun `a fetch that keeps failing still ends in a give-up, not a hang`() = runTest {
        val clock = FakeClock()
        val outcome = clock.echo().awaitSettled(WidgetKind.LOCK, before = "locked") { null }
        assertEquals(EchoOutcome.GaveUp(null), outcome)
    }

    @Test
    fun `an alarm settles only once its exit delay finishes`() = runTest {
        val clock = FakeClock()
        val states = ArrayDeque(listOf("arming", "arming", "armed_away"))
        val outcome = clock.echo().awaitSettled(WidgetKind.ALARM, before = "disarmed") {
            states.removeFirstOrNull()
        }
        assertEquals(EchoOutcome.Settled("armed_away"), outcome)
    }

    @Test
    fun `a light settles on its first change`() = runTest {
        val clock = FakeClock()
        val outcome = clock.echo().awaitSettled(WidgetKind.LIGHT, before = "off") { "on" }
        assertEquals(EchoOutcome.Settled("on"), outcome)
        assertEquals(1, clock.slept.size)
    }
}
