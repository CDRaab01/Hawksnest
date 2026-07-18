package com.hawksnest.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One-shot bounded HTTP probe of a base URL's host — is the machine answering *at all* over the
 * current network (i.e. the Tailscale tunnel is up and routing)? Any HTTP response — even a
 * 401/404 — counts as reachable; only a transport failure (no route, refused, timeout) or a
 * malformed URL is unreachable. Extracted from `SettingsViewModel.testReachability` so the
 * Settings "Test" button and the reconnect loop's passive offline hint share one probe.
 */
class ReachabilityProbe internal constructor(private val calls: Call.Factory) {

    /** True when the host produced any HTTP response; false on malformed URL / transport failure. */
    suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = url.trim()
            val req = Request.Builder().url(target.trimEnd('/') + "/").get().build()
            calls.newCall(req).execute().use { true }
        } catch (_: IllegalArgumentException) {
            false // malformed URL
        } catch (_: Exception) {
            false // UnknownHost / connect refused / timeout → not reachable
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 8L

        /**
         * Build a probe from the shared client. The shared client has no read timeout (the WS is
         * long-lived) and a probe must never hang, so it gets its own bounded-timeout copy —
         * cheap: the connection pool and dispatcher are shared.
         */
        fun from(client: OkHttpClient): ReachabilityProbe = ReachabilityProbe(
            client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }
}
