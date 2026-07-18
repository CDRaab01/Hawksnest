package com.hawksnest.core.ha

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the skippable-backoff wait the reconnect loop uses: the wait runs the full backoff by
 * default, a "Retry now" releases it immediately, and a stale signal (raised while a connect
 * attempt was already in flight) is drained so it can't skip a future backoff.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RetrySignalTest {

    @Test
    fun `waits out the full backoff when never signalled`() = runTest {
        val signal = RetrySignal()
        val wait = async { signal.awaitOrTimeout(30_000) }
        advanceTimeBy(29_999)
        runCurrent()
        assertFalse(wait.isCompleted) // still waiting just before the deadline
        advanceTimeBy(2)
        runCurrent()
        assertEquals(false, wait.await()) // timed out, not skipped
    }

    @Test
    fun `a signal releases the wait immediately`() = runTest {
        val signal = RetrySignal()
        val wait = async { signal.awaitOrTimeout(30_000) }
        runCurrent()
        signal.signal()
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(true, wait.await())
    }

    @Test
    fun `repeated taps collapse into one release`() = runTest {
        val signal = RetrySignal()
        signal.signal()
        signal.signal()
        signal.signal()
        val first = async { signal.awaitOrTimeout(1_000) }
        runCurrent()
        assertEquals(true, first.await())
        // The extra taps were conflated away — the next wait times out normally.
        val second = async { signal.awaitOrTimeout(1_000) }
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(false, second.await())
    }

    @Test
    fun `drain discards a stale signal so it can't skip a future backoff`() = runTest {
        val signal = RetrySignal()
        signal.signal() // tapped while a connect attempt was already in flight
        signal.drain()
        val wait = async { signal.awaitOrTimeout(5_000) }
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(false, wait.await())
    }
}
