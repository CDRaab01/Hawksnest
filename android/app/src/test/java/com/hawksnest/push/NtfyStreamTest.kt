package com.hawksnest.push

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reconnect policy, end to end: what a session is judged to have done ([NtfyStream], against
 * a real socket) and what wait that earns ([NtfyBackoff]).
 *
 * The regression these pin is a 502 from the TLS front being read as "the server closed a healthy
 * stream" — a request every two seconds, forever. Each test below is one way of ending a session,
 * and the assertion is always the same question: does the wait grow, or reset?
 */
class NtfyStreamTest {

    private lateinit var server: MockWebServer
    private var clock = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stream() = NtfyStream(OkHttpClient()) { clock }
    private fun url() = server.url("/hawksnest/json").toString()

    // ── NtfyStream ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an HTTP error throws instead of reading as a clean close`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        val lines = mutableListOf<String>()
        val e = assertFailsWith<NtfyStreamException> { stream().listen(url()) { lines += it } }
        assertEquals("ntfy HTTP 502", e.message)
        // The error page's body must never reach the message parser.
        assertTrue(lines.isEmpty())
        assertEquals(0, e.session.linesRead)
    }

    @Test
    fun `lines are delivered in order and counted, keepalives included`() {
        server.enqueue(
            MockResponse().setBody(
                """{"event":"open"}""" + "\n" + """{"event":"keepalive"}""" + "\n" +
                    """{"event":"message","message":"Doorbell"}""" + "\n"
            )
        )
        val lines = mutableListOf<String>()
        val session = stream().listen(url()) { lines += it }
        assertEquals(3, session.linesRead)
        assertEquals("""{"event":"message","message":"Doorbell"}""", lines.last())
    }

    @Test
    fun `a clean close with nothing read reports zero lines`() {
        server.enqueue(MockResponse().setBody(""))
        assertEquals(0, stream().listen(url()) { }.linesRead)
    }

    @Test
    fun `a drop mid-stream keeps what the session achieved`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"event":"open"}""" + "\n" + "x".repeat(4096))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val e = assertFailsWith<NtfyStreamException> { stream().listen(url()) { } }
        assertEquals(1, e.session.linesRead)
    }

    @Test
    fun `a connection refused is a session that did nothing`() {
        val dead = url()
        server.shutdown()
        val e = assertFailsWith<NtfyStreamException> { stream().listen(dead) { } }
        assertEquals(0, e.session.linesRead)
    }

    // ── NtfyBackoff ──────────────────────────────────────────────────────────────────────────

    private fun backoff() = NtfyBackoff(minMs = 2_000, maxMs = 300_000, healthyAfterMs = 60_000)
    private val failed = NtfySession(linesRead = 0, durationMs = 40)
    private val healthy = NtfySession(linesRead = 12, durationMs = 3_600_000)

    @Test
    fun `repeated failures grow the wait to the cap and stay there`() {
        val b = backoff()
        val waits = List(10) { b.next(failed) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L, 300_000L), waits)
    }

    @Test
    fun `a healthy session that drops reconnects promptly whatever came before`() {
        val b = backoff()
        repeat(6) { b.next(failed) }
        assertEquals(2_000L, b.next(healthy))
        // …and the failure after it starts the climb again from the bottom, not from where it was.
        assertEquals(4_000L, b.next(failed))
    }

    @Test
    fun `a clean close with no lines backs off like any other failure`() {
        val b = backoff()
        b.next(failed)
        assertEquals(4_000L, b.next(NtfySession(linesRead = 0, durationMs = 5)))
    }

    @Test
    fun `an open frame followed by an immediate hang-up is not health`() {
        // ntfy sends `open` on accept, so lines alone would call this healthy on every attempt
        // and rebuild the 2s loop one layer up.
        val b = backoff()
        val acceptedThenDropped = NtfySession(linesRead = 1, durationMs = 300)
        assertFalse(b.isHealthy(acceptedThenDropped))
        assertEquals(listOf(2_000L, 4_000L, 8_000L), List(3) { b.next(acceptedThenDropped) })
    }

    @Test
    fun `a silent connection that timed out is not health either`() {
        assertFalse(backoff().isHealthy(NtfySession(linesRead = 0, durationMs = 75_000)))
    }

    @Test
    fun `a network change makes the next attempt prompt`() {
        val b = backoff()
        repeat(8) { b.next(failed) }
        b.reset()
        assertEquals(2_000L, b.next(failed))
    }

    @Test
    fun `the 502 loop end to end - every attempt waits longer than the last`() {
        val stream = stream()
        val b = backoff()
        val waits = List(4) {
            server.enqueue(MockResponse().setResponseCode(502))
            val session = try {
                stream.listen(url()) { }
            } catch (e: NtfyStreamException) {
                e.session
            }
            b.next(session)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), waits)
    }
}
