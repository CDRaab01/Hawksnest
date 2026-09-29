package com.hawksnest.core.net

import com.hawksnest.core.logic.DirectStreams
import com.hawksnest.core.logic.parseDirectStreams
import com.hawksnest.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The direct-stream settings the Hawksnest server hands out (`/hawksnest/direct-streams`), so a
 * signed-in phone needs no camera setup of its own. See deploy/render-direct-streams.sh for where
 * they come from and deploy/nginx.conf for the token gate.
 *
 * Asked with the same Home Assistant token the app already holds, from the same origin it already
 * talks to. Kept in memory only: it holds a password, and the next fetch is cheap. A server that
 * doesn't provide it (404, pointed straight at HA, an older deploy) is simply "not provided", and
 * the tier behaves as it did before this existed.
 */
@Singleton
class DirectStreamSource @Inject constructor(
    okHttpClient: OkHttpClient,
    private val credentialStore: CredentialStore,
) {
    // Bounded: the shared client has no read timeout (it carries the HA WebSocket), and this must
    // never hold up opening a camera.
    private val client = okHttpClient.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private var cached: DirectStreams? = null
    private var cachedFor: String? = null
    private var expiresAtMs = 0L

    private val _provided = MutableStateFlow<DirectStreams?>(null)

    /** What the server last provided, for Settings to show ("from your Hawksnest server"). */
    val provided: StateFlow<DirectStreams?> = _provided.asStateFlow()

    /**
     * The server's settings, fetched at most every [FRESH_MS] (or [RETRY_MS] after a failure).
     * Re-fetched at once when the app is pointed at a different server or token.
     */
    suspend fun get(): DirectStreams? = mutex.withLock {
        val base = credentialStore.haUrl.firstOrNull()?.trim()?.trimEnd('/').orEmpty()
        val token = credentialStore.haToken.firstOrNull()?.trim().orEmpty()
        if (base.isEmpty() || token.isEmpty()) {
            publish(null, key = null, ttlMs = 0)
            return@withLock null
        }
        // Keyed on the server and the token, without holding the token itself as the key.
        val key = "$base#${token.hashCode()}"
        val now = System.currentTimeMillis()
        if (key == cachedFor && now < expiresAtMs) return@withLock cached

        when (val result = fetch(base, token)) {
            is Fetch.Provided -> publish(result.streams, key, FRESH_MS)
            Fetch.NotProvided -> publish(null, key, FRESH_MS)
            // A blip shouldn't switch the tier off: keep what this server last gave, retry soon.
            Fetch.Failed -> publish(if (key == cachedFor) cached else null, key, RETRY_MS)
        }
        cached
    }

    /** Drop what the server provided (on disconnect), so the camera password leaves memory with the
     *  token that fetched it. The next [get] asks again. */
    suspend fun forget() = mutex.withLock { publish(null, key = null, ttlMs = 0) }

    private fun publish(streams: DirectStreams?, key: String?, ttlMs: Long) {
        cached = streams
        cachedFor = key
        expiresAtMs = System.currentTimeMillis() + ttlMs
        _provided.value = streams
    }

    private sealed interface Fetch {
        data class Provided(val streams: DirectStreams) : Fetch
        data object NotProvided : Fetch
        data object Failed : Fetch
    }

    private suspend fun fetch(base: String, token: String): Fetch = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$base$PATH")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { res ->
                when {
                    res.isSuccessful ->
                        res.body?.string()?.let(::parseDirectStreams)?.let { Fetch.Provided(it) } ?: Fetch.NotProvided
                    // 404: this server has none. 401: the token doesn't open it. Neither changes
                    // with a quick retry.
                    res.code == 404 || res.code == 401 -> Fetch.NotProvided
                    else -> Fetch.Failed
                }
            }
        } catch (_: Exception) {
            Fetch.Failed
        }
    }

    private companion object {
        const val PATH = "/hawksnest/direct-streams"
        const val TIMEOUT_SECONDS = 5L
        const val FRESH_MS = 30 * 60_000L
        const val RETRY_MS = 60_000L
    }
}
