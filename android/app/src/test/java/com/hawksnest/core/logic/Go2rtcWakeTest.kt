package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Go2rtcWakeTest {

    @Test
    fun `an awake camera steps down on a source error, never retries`() {
        assertNull(sourceErrorRetryDelayMs(wakeable = false, elapsedMs = 0))
        assertNull(sourceErrorRetryDelayMs(wakeable = false, elapsedMs = 500))
    }

    @Test
    fun `a sleeping camera retries every second inside the wake budget`() {
        assertEquals(WAKE_RETRY_MS, sourceErrorRetryDelayMs(wakeable = true, elapsedMs = 0))
        assertEquals(WAKE_RETRY_MS, sourceErrorRetryDelayMs(wakeable = true, elapsedMs = 15_000))
        assertEquals(WAKE_RETRY_MS, sourceErrorRetryDelayMs(wakeable = true, elapsedMs = 28_999))
    }

    @Test
    fun `a sleeping camera gives up once the next attempt would land past the budget`() {
        assertNull(sourceErrorRetryDelayMs(wakeable = true, elapsedMs = 29_000))
        assertNull(sourceErrorRetryDelayMs(wakeable = true, elapsedMs = 45_000))
    }

    @Test
    fun `negative elapsed (clock step) is treated as zero`() {
        assertEquals(WAKE_RETRY_MS, sourceErrorRetryDelayMs(wakeable = true, elapsedMs = -5_000))
        assertEquals(WAKE_BUDGET_MS, go2rtcWatchdogMs(wakeable = true, elapsedMs = -5_000))
    }

    @Test
    fun `awake watchdog is always the short leash`() {
        assertEquals(CONNECT_WATCHDOG_MS, go2rtcWatchdogMs(wakeable = false, elapsedMs = 0))
        assertEquals(CONNECT_WATCHDOG_MS, go2rtcWatchdogMs(wakeable = false, elapsedMs = 20_000))
    }

    @Test
    fun `sleeping watchdog shares the budget but never drops below the short leash`() {
        assertEquals(30_000L, go2rtcWatchdogMs(wakeable = true, elapsedMs = 0))
        assertEquals(18_000L, go2rtcWatchdogMs(wakeable = true, elapsedMs = 12_000))
        assertEquals(CONNECT_WATCHDOG_MS, go2rtcWatchdogMs(wakeable = true, elapsedMs = 25_000))
        assertEquals(CONNECT_WATCHDOG_MS, go2rtcWatchdogMs(wakeable = true, elapsedMs = 60_000))
    }

    @Test
    fun `constants match the web twin`() {
        // src/lib/go2rtcWake.ts — the platforms must agree on how long a wake gets.
        assertEquals(1_000L, WAKE_RETRY_MS)
        assertEquals(30_000L, WAKE_BUDGET_MS)
        assertEquals(8_000L, CONNECT_WATCHDOG_MS)
    }
}
