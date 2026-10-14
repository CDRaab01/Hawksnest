package com.hawksnest.push

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/*
 * The two halves of the push listener that can be reasoned about without a Service: what one
 * streaming session did ([NtfyStream]) and how long to wait before the next one ([NtfyBackoff]).
 * They live outside `NtfyPushService` because that class can only be proven on a phone, and the
 * bug these replace was exactly the kind a unit test catches and a phone hides: a retry loop that
 * is invisible until someone reads a battery graph.
 */

/** What one streaming session amounted to, as far as the retry policy cares. */
data class NtfySession(
    /** Lines read before the stream ended — keepalives and the `open` frame count. */
    val linesRead: Int,
    /** Wall-clock time from the request going out to the stream ending. */
    val durationMs: Long,
)

/**
 * One connection to `<base>/<topic>/json`, read line by line until it ends.
 *
 * A non-2xx response THROWS. It used to fall through: an error page has a short body,
 * `readUtf8Line()` hit end-of-stream at once, and the caller read that as "the server closed a
 * healthy stream, reconnect promptly". A 502 from the Tailscale Serve front while the ntfy
 * forwarder behind it is dead — which is the state after every host reboot — therefore became an
 * HTTPS request every two seconds, forever, from a foreground service with the screen off.
 */
class NtfyStream(
    private val client: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Blocks until the stream ends. Returns normally when the server closes it; throws
     * [IOException] when the response is an HTTP error or the connection drops mid-read — with
     * the session so far attached, because "dropped after an hour" and "never connected" must not
     * be retried the same way.
     *
     * [onCall] hands the in-flight call to the caller so it can be cancelled from another thread;
     * a blocking read does not notice coroutine cancellation on its own.
     */
    @Throws(NtfyStreamException::class)
    fun listen(
        url: String,
        onCall: (Call) -> Unit = {},
        keepGoing: () -> Boolean = { true },
        onLine: (String) -> Unit,
    ): NtfySession {
        val startedAt = nowMs()
        var lines = 0
        try {
            val call = client.newCall(Request.Builder().url(url).get().build())
            onCall(call)
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("ntfy HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("ntfy response had no body")
                while (keepGoing()) {
                    val line = source.readUtf8Line() ?: break // null = stream closed
                    lines++
                    onLine(line)
                }
            }
        } catch (e: IOException) {
            throw NtfyStreamException(NtfySession(lines, nowMs() - startedAt), e)
        }
        return NtfySession(lines, nowMs() - startedAt)
    }
}

/** A session that ended in an error, carrying what it achieved before it did. */
class NtfyStreamException(val session: NtfySession, cause: IOException) :
    IOException(cause.message, cause)

/**
 * How long to wait before the next connection attempt.
 *
 * The rule: **the wait only returns to [minMs] once a stream has proven healthy.** How a session
 * ended says nothing — a clean close straight after connecting is as much a failure as an
 * exception, and a read timeout after a working hour is as recoverable as a clean close. What a
 * session *did* is the signal, so that is what [next] is asked about.
 *
 * Not thread-safe; owned by the one coroutine that runs the reconnect loop.
 */
class NtfyBackoff(
    private val minMs: Long = MIN_BACKOFF_MS,
    private val maxMs: Long = MAX_BACKOFF_MS,
    private val healthyAfterMs: Long = HEALTHY_AFTER_MS,
) {
    private var currentMs = minMs

    /**
     * Did this session prove the path works? It must have read something AND outlived one ntfy
     * keepalive interval.
     *
     * Lines alone are not proof: ntfy sends an `open` frame the moment it accepts a subscriber,
     * so a server (or a proxy in front of it) that accepts and immediately hangs up would read as
     * healthy on every attempt and rebuild the very loop this class exists to prevent. Duration
     * alone is not proof either: a connection that sat silent until the 75s read timeout carried
     * nothing, not even a keepalive.
     */
    fun isHealthy(session: NtfySession): Boolean =
        session.linesRead > 0 && session.durationMs >= healthyAfterMs

    /** The wait before the next attempt, given how the last session went. Grows on each call. */
    fun next(session: NtfySession): Long {
        if (isHealthy(session)) currentMs = minMs
        val wait = currentMs
        currentMs = (currentMs * 2).coerceAtMost(maxMs)
        return wait
    }

    /**
     * The network just (re)appeared. Whatever failures were counted belong to the old network —
     * most of them *were* the old network — so the next attempt is prompt. This is what lets
     * [maxMs] be long without push going quiet for minutes after walking back into Wi-Fi.
     */
    fun reset() {
        currentMs = minMs
    }

    companion object {
        const val MIN_BACKOFF_MS = 2_000L

        /**
         * Five minutes. Reached only by failing repeatedly *with a network up*, which on this
         * setup means the server side is down (the host rebooting, the ntfy forwarder dead) — an
         * outage measured in minutes to hours that no amount of retrying shortens. At the cap
         * that is 12 requests an hour; the old 60s cap was 60, and the bug this replaces was
         * 1,800. The price is that push resumes up to five minutes after the server does. Losing
         * and regaining the *network* never pays it — see [reset].
         */
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** A little over ntfy's ~45s keepalive, so a healthy stream has carried at least one. */
        const val HEALTHY_AFTER_MS = 60_000L
    }
}
