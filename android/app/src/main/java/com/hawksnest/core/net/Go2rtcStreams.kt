package com.hawksnest.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which streams go2rtc is currently serving, plus the media circuit-breaker — the 1:1 Android port
 * of the cache half of the web `src/lib/go2rtc.ts`. (`go2rtcWsUrl` stays in `ui/cameras/Go2rtc.kt`;
 * this lives in `core` because `core` must not depend on `ui`.)
 *
 * ## Why the app has to ask
 *
 * The live ladder's top tier is WebRTC straight to go2rtc, and attempting it for a stream go2rtc
 * does not serve costs the player's full 8-second watchdog before it steps down — on every open.
 * Android used to dodge that by gating the tier on `isRing`, which was accurate only while go2rtc
 * served Ring cameras exclusively. It no longer does (the Reolink main streams are go2rtc streams
 * too), so the gate now asks go2rtc what it actually has, exactly as the web client does.
 *
 * ## Failure is a cached NO, deliberately
 *
 * Any failure — unreachable, non-2xx, unparseable — caches an EMPTY set rather than leaving the
 * cache unset. A go2rtc that is down would otherwise cost every camera that 8-second stall; an
 * empty set skips the tier outright and goes straight to the HA path. It self-heals: the entry
 * expires after [STREAMS_TTL_MS] and the next open re-fetches.
 *
 * Note the asymmetry with `core/logic/Frigate`-style membership, which fails CLOSED: guessing
 * wrong here costs one fast step-down, so before the list is known this is optimistic.
 */
@Singleton
class Go2rtcStreams internal constructor(
    private val fetchStreams: suspend (String) -> Set<String>?,
    private val nowMs: () -> Long,
) {

    @Inject
    constructor(client: OkHttpClient) : this(httpFetcher(client), System::currentTimeMillis)

    // null = never fetched (be optimistic); a Set = known. Volatile because `maybeAvailable` is a
    // synchronous read from the composition thread while `prime` writes from an IO coroutine.
    @Volatile
    private var streamsCache: Set<String>? = null
    private var fetchedAt = 0L

    // Web dedupes concurrent primes by sharing one in-flight Promise. A Mutex gets the same
    // guarantee more simply here: callers queue, and everyone after the first finds the cache
    // fresh and returns without a second request.
    private val mutex = Mutex()

    /**
     * Populate the cache if it is missing or stale; safe to call on every camera open. Returns once
     * the cache is authoritative, so a caller can await an accurate [maybeAvailable] instead of
     * acting on its optimistic default.
     */
    suspend fun prime(baseUrl: String) {
        if (baseUrl.isBlank()) return
        mutex.withLock {
            if (streamsCache != null && nowMs() - fetchedAt < STREAMS_TTL_MS) return
            streamsCache = fetchStreams(baseUrl) ?: emptySet()
            fetchedAt = nowMs()
        }
    }

    /** Whether the stream list has been fetched at least once (i.e. the answer is authoritative). */
    fun streamsKnown(): Boolean = streamsCache != null

    /**
     * Synchronous best-guess for whether the go2rtc tier is worth attempting for [src]:
     *  - never, once media is known-broken this session (the circuit-breaker);
     *  - never, if the list is known and does not include [src];
     *  - otherwise yes — an absent stream fails its WebSocket negotiation fast and steps down.
     */
    fun maybeAvailable(src: String): Boolean {
        if (!Go2rtcHealth.maybeAvailable()) return false
        val known = streamsCache
        if (known != null && !known.contains(src)) return false
        return true
    }

    /** Test seam: drop the cached list so cases don't leak into each other. */
    internal fun resetForTest() {
        streamsCache = null
        fetchedAt = 0L
    }

    companion object {
        internal const val STREAMS_TTL_MS = 60_000L
        private const val TIMEOUT_SECONDS = 8L

        /**
         * Reads the list from the same origin (and the same nginx `/go2rtc/` proxy) the signaling
         * WebSocket already uses, so it needs no new host and no credential — reaching the
         * tailnet-only proxy is the authorization, as with [RingTimelineClient].
         *
         * Returns null on any failure, which the caller turns into a cached empty set. The shared
         * client has no read timeout (the HA WebSocket is long-lived) and this must never hang, so
         * it takes its own bounded copy — cheap, since the pool and dispatcher are shared.
         */
        private fun httpFetcher(client: OkHttpClient): suspend (String) -> Set<String>? {
            val bounded = client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val json = Json { ignoreUnknownKeys = true }
            return { baseUrl ->
                withContext(Dispatchers.IO) {
                    try {
                        val url = baseUrl.trimEnd('/') + "/go2rtc/api/streams"
                        bounded.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                            val body = if (res.isSuccessful) res.body?.string() else null
                            body?.let {
                                (json.parseToJsonElement(it) as? JsonObject)?.keys?.toSet()
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }
}

/**
 * Session circuit-breaker for the go2rtc **media** path. Signaling (WebSocket via nginx) can succeed
 * while media (WebRTC to `GO2RTC_HOST_IP:8555`) cannot be reached — before the §7c host forwarder is
 * up, or off the tailnet. The first camera whose media fails flips this false and every camera after
 * skips the go2rtc tier for the rest of the process (no repeated multi-second stalls); it drops to
 * the HA WebRTC path instead. A success flips it true.
 *
 * Stays a process-global object rather than an injected dependency because it is reported from
 * go2rtc's signaling thread and read from composition — the same shape the web module uses.
 */
object Go2rtcHealth {
    @Volatile
    private var mediaHealthy: Boolean? = null

    fun report(ok: Boolean) {
        mediaHealthy = ok
    }

    /** Best-guess for whether the direct-go2rtc tier is worth attempting (media not known-broken). */
    fun maybeAvailable(): Boolean = mediaHealthy != false

    /** Test seam: forget this session's media verdict. */
    internal fun resetForTest() {
        mediaHealthy = null
    }
}
