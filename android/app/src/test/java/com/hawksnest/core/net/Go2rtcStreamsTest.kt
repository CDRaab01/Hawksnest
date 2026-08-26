package com.hawksnest.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the web `src/lib/__tests__/go2rtc.test.ts` case-for-case, plus the TTL and concurrent-
 * prime cases that are only expressible on this side (the web module dedupes with a shared
 * Promise, this one with a Mutex).
 */
class Go2rtcStreamsTest {

    private var now = 1_000L
    private var calls = 0
    private var served: Set<String>? = emptySet()

    /** A fetcher that records its call count and returns whatever the case set up. */
    private fun subject(): Go2rtcStreams = Go2rtcStreams(
        fetchStreams = { calls++; served },
        nowMs = { now },
    )

    @BeforeTest
    @AfterTest
    fun resetBreaker() {
        // Process-global, like the web module's — a leaked verdict would silently disable the tier
        // for every case after it.
        Go2rtcHealth.resetForTest()
    }

    @Test
    fun `is optimistic before the stream list is known, then honors the circuit-breaker`() {
        val streams = subject()

        // Nothing fetched yet → assume available (a wrong guess costs one fast step-down).
        assertTrue(streams.maybeAvailable("front_door"))
        assertFalse(streams.streamsKnown())

        // A media failure trips the breaker.
        Go2rtcHealth.report(false)
        assertFalse(streams.maybeAvailable("front_door"))

        // A success clears it.
        Go2rtcHealth.report(true)
        assertTrue(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `the breaker expires, so a transient failure cannot disable the tier forever`() {
        val streams = subject()
        var clock = 1_000L
        Go2rtcHealth.setClockForTest { clock }

        Go2rtcHealth.report(false)
        assertFalse(streams.maybeAvailable("front_door"))

        // Still suppressed just before the TTL.
        clock += Go2rtcHealth.BREAKER_TTL_MS - 1
        assertFalse(streams.maybeAvailable("front_door"))

        // ...and retryable once it elapses. Without this the tier was unreachable for the whole
        // process: `maybeAvailable` gates mounting the player, so the `report(true)` that would
        // clear the verdict could never fire. The observed symptom was the live ladder falling
        // all the way to a 10s-refresh still image behind a green "Live" badge.
        clock += 2
        assertTrue(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `a success after expiry keeps the tier available`() {
        val streams = subject()
        var clock = 1_000L
        Go2rtcHealth.setClockForTest { clock }

        Go2rtcHealth.report(false)
        clock += Go2rtcHealth.BREAKER_TTL_MS + 1
        assertTrue(streams.maybeAvailable("front_door"))

        // The retry succeeds: the verdict is cleared outright, not merely expired, so a later
        // clock reading can't resurrect it.
        Go2rtcHealth.report(true)
        clock += 1
        assertTrue(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `skips a camera go2rtc isn't serving once the list is known`() = runTest {
        served = setOf("front_door", "big_room")
        val streams = subject()
        streams.prime("https://ha.local")

        assertTrue(streams.streamsKnown())
        assertTrue(streams.maybeAvailable("front_door"))
        // A Reolink stream go2rtc serves is now available to the tier — the whole point of the
        // change; the old isRing gate said no here.
        assertTrue(streams.maybeAvailable("big_room"))
        assertFalse(streams.maybeAvailable("garage")) // not in go2rtc
    }

    @Test
    fun `skips the go2rtc tier entirely when the list can't be fetched (go2rtc down)`() = runTest {
        served = null // transport failure / non-2xx / unparseable
        val streams = subject()
        streams.prime("https://ha.local")

        // A failed fetch caches an EMPTY set rather than leaving the cache unset: go2rtc is
        // unreachable, so attempting it would just cost the 8s watchdog on every camera. Go
        // straight to the HA path instead.
        assertTrue(streams.streamsKnown())
        assertFalse(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `a blank base url is not a fetch, and leaves the answer optimistic`() = runTest {
        val streams = subject()
        streams.prime("")

        // Demo mode / pre-connection. Caching an empty set here would wrongly disable the tier for
        // the rest of the TTL once a real connection arrives.
        assertEquals(0, calls)
        assertFalse(streams.streamsKnown())
        assertTrue(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `re-primes only after the TTL, so a recovered go2rtc self-heals`() = runTest {
        served = null
        val streams = subject()
        streams.prime("https://ha.local")
        assertFalse(streams.maybeAvailable("front_door"))
        assertEquals(1, calls)

        // Within the TTL the cached NO stands, without re-asking.
        now += Go2rtcStreams.STREAMS_TTL_MS - 1
        streams.prime("https://ha.local")
        assertEquals(1, calls)

        // Past it, go2rtc is asked again — and once it answers, the tier comes back.
        now += 2
        served = setOf("front_door")
        streams.prime("https://ha.local")
        assertEquals(2, calls)
        assertTrue(streams.maybeAvailable("front_door"))
    }

    @Test
    fun `concurrent primes share one fetch`() = runTest {
        served = setOf("front_door")
        val streams = Go2rtcStreams(
            fetchStreams = { calls++; delay(50); served },
            nowMs = { now },
        )

        // Opening several cameras at once must not fire a request per camera.
        listOf(
            async { streams.prime("https://ha.local") },
            async { streams.prime("https://ha.local") },
            async { streams.prime("https://ha.local") },
        ).awaitAll()

        assertEquals(1, calls)
        assertTrue(streams.maybeAvailable("front_door"))
    }
}
