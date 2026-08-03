package com.hawksnest.crash

import com.hawksnest.core.logic.scrubSecrets
import com.hawksnest.push.PushSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes crashes captured on a previous run to the ntfy topic the app already uses.
 *
 * Reuses the push endpoint rather than adding a second one: it is already deployed, already
 * reachable only over the tailnet, already the thing that reaches this phone, and the user has
 * already opted into notifications from it. A second transport would be a second thing to
 * configure, secure and forget about.
 *
 * **Gated on the push toggle being ON.** Someone who turned notifications off has said they do
 * not want this app pushing to their phone, and "except for crashes" is not a distinction they
 * agreed to. Reports are still captured to disk and visible in Settings either way — turning push
 * on later sends whatever is pending, because the sent-marker is per report and not time-based.
 *
 * Failures are silent by design. This runs at startup on a best-effort basis; a crash reporter
 * that complains about its own network problems is noise on top of an existing bad day.
 */
@Singleton
class CrashUploader @Inject constructor(
    private val store: CrashStore,
    private val pushSettings: PushSettings,
    private val client: OkHttpClient,
) {
    suspend fun sendPending() = withContext(Dispatchers.IO) {
        val pending = store.unsent()
        if (pending.isEmpty()) return@withContext
        if (!pushSettings.enabled.first()) return@withContext

        val base = pushSettings.baseUrl.first().trimEnd('/')
        val topic = pushSettings.topic.first()
        if (base.isBlank() || topic.isBlank()) return@withContext

        // Short timeouts: the app OkHttp client has NO read timeout (it carries the long-lived
        // websocket), which would let a startup upload hang indefinitely.
        val http = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        for (file in pending) {
            val body = store.read(file)
            if (body.isBlank()) { store.markSent(file); continue }
            val ok = runCatching {
                val req = Request.Builder()
                    .url("$base/$topic")
                    // Already scrubbed at write time; scrubbed again here so a report written by
                    // an older build can never leak through a newer one.
                    .post(scrubSecrets(body).toRequestBody())
                    .header("Title", "Hawksnest crashed")
                    .header("Priority", "high")
                    .header("Tags", "boom")
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            // Only mark on success, so a transient outage retries next launch rather than
            // silently dropping the one report that mattered.
            if (ok) store.markSent(file)
        }
    }
}
