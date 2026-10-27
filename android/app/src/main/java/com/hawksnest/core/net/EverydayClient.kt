package com.hawksnest.core.net

import com.hawksnest.core.logic.EverydayDay
import com.hawksnest.core.logic.EverydayReel
import com.hawksnest.core.logic.EverydaySummary
import com.hawksnest.core.logic.parseEverydayDay
import com.hawksnest.core.logic.parseEverydayDays
import com.hawksnest.core.logic.parseEverydayReels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads/writes the `everyday` service (sibling repo `hawksnest-automation`) through the same
 * Hawksnest nginx origin as [RingTimelineClient], but — unlike ring-timeline — **with the HA
 * token**: nginx gates `/everyday/` behind an HA-token `auth_request` because it serves interior
 * footage of the family and accepts writes. Media is played by ExoPlayer/Coil with the same
 * `Authorization` header (see [mediaUrl]), so no token ever rides in a URL.
 *
 * Calls throw [EverydayException] with human copy; the screen shows it. Mirrors `lib/everyday.ts`.
 */
@Singleton
class EverydayClient @Inject constructor(client: OkHttpClient) {

    /** Bounded: the shared client has `readTimeout(0)` for the WebSocket (see RingTimelineClient). */
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun base(baseUrl: String) = baseUrl.trimEnd('/') + PATH

    /** Absolute URL for a service-relative media path (`media/...`). */
    fun mediaUrl(baseUrl: String, rel: String): String = "${base(baseUrl)}/$rel"

    private suspend fun call(baseUrl: String, token: String, path: String, postBody: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url("${base(baseUrl)}/$path")
                .header("Authorization", "Bearer $token")
            if (postBody != null) builder.post(postBody.toRequestBody(JSON_TYPE))
            try {
                client.newCall(builder.build()).execute().use { res ->
                    if (!res.isSuccessful) throw EverydayException(describeEverydayStatus(res.code))
                    val text = res.body?.string().orEmpty()
                    json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
                }
            } catch (e: EverydayException) {
                throw e
            } catch (e: Exception) {
                throw EverydayException("Can't reach the everyday service.")
            }
        }

    suspend fun days(baseUrl: String, token: String, limit: Int = 30): List<EverydaySummary> =
        parseEverydayDays(call(baseUrl, token, "api/days?limit=$limit"))

    suspend fun day(baseUrl: String, token: String, date: String): EverydayDay =
        parseEverydayDay(call(baseUrl, token, "api/days/$date"))

    suspend fun reels(baseUrl: String, token: String): List<EverydayReel> =
        parseEverydayReels(call(baseUrl, token, "api/reel"))

    suspend fun pick(baseUrl: String, token: String, date: String, eventId: String): EverydayDay =
        parseEverydayDay(
            call(baseUrl, token, "api/days/$date/pick", JsonObject(mapOf("eventId" to JsonPrimitive(eventId))).toString()),
        )

    suspend fun scan(baseUrl: String, token: String, date: String) {
        call(baseUrl, token, "api/days/$date/scan", "{}")
    }

    private companion object {
        const val PATH = "/everyday"
        const val TIMEOUT_SECONDS = 20L
        val JSON_TYPE = "application/json".toMediaType()
    }
}

class EverydayException(message: String) : Exception(message)

/** Same copy as the web `describeStatus`. */
fun describeEverydayStatus(code: Int): String = when (code) {
    401, 403 -> "Home Assistant rejected the token."
    404 -> "Not scanned yet."
    502, 503, 504 -> "The everyday service isn't running."
    else -> "Request failed ($code)."
}
