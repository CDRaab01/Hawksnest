package com.hawksnest.widget.data

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The widgets' own path to Home Assistant — plain REST, not the app's WebSocket stack.
 *
 * The app's data layer is live-socket-only: `HaSource` streams `subscribe_entities` deltas into
 * `HaState` and there is no on-demand fetch. That model is right for a screen and wrong for a
 * widget, which is drawn by a broadcast into a process that may have just been created and will
 * be killed again shortly. Standing up the full socket (auth handshake, three registry loads,
 * reconnect loop) to answer "is the door locked?" would cost seconds and battery for one reading.
 *
 * So widgets speak HA's REST API directly, the same way `HaSource` already does for Frigate and
 * automation config. Everything is a single request with a hard timeout, and every failure is a
 * [WidgetBlocker] the widget can draw honestly rather than an exception.
 */

/** A REST call's result: a value, or a reason the widget can render. */
sealed interface HaCall<out T> {
    data class Ok<T>(val value: T) : HaCall<T>
    data class Failed(val blocker: WidgetBlocker) : HaCall<Nothing>
}

data class HaCredentials(val baseUrl: String, val token: String)

/**
 * Where the widget layer gets its credentials. An interface so the client is unit-testable
 * without DataStore or the Android Keystore.
 */
interface CredentialSource {
    suspend fun current(): HaCredentials?
}

/** The real source: the app's Keystore-wrapped [CredentialStore]. */
@Singleton
class StoredCredentialSource @Inject constructor(
    private val store: CredentialStore,
) : CredentialSource {
    override suspend fun current(): HaCredentials? {
        // Both are suspending DataStore reads; the token is decrypted on read by CredentialStore.
        val url = store.haUrl.firstOrNull()?.trim()
        val token = store.haToken.firstOrNull()?.trim()
        if (url.isNullOrEmpty() || token.isNullOrEmpty()) return null
        return HaCredentials(url, token)
    }
}

@Singleton
class WidgetHaClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialSource,
) {
    /**
     * The app client has no read timeout — its socket is meant to live forever. A widget request
     * must not: an action callback gets roughly ten seconds of guaranteed execution, and a request
     * that never returns would strand a pending spinner on the home screen.
     */
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /** One entity's current state. */
    suspend fun state(entityId: String): HaCall<HassEntity> =
        get("states/$entityId") { body -> json.decodeFromString(HassEntity.serializer(), body) }

    /** Every entity HA exposes — used only by the configuration screen's picker. */
    suspend fun states(): HaCall<List<HassEntity>> =
        get("states") { body ->
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HassEntity.serializer()), body)
        }

    /** Fire a service call, e.g. `light` / `turn_on` with `brightness_pct`. */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        extra: Map<String, Any?> = emptyMap(),
    ): HaCall<Unit> {
        val creds = credentials.current() ?: return HaCall.Failed(WidgetBlocker.SIGNED_OUT)
        val payload = serviceBody(entityId, extra)
        return withContext(Dispatchers.IO) {
            request(
                Request.Builder()
                    .url("${creds.baseUrl.trimEnd('/')}/api/services/$domain/$service")
                    .header("Authorization", "Bearer ${creds.token}")
                    .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
                    .build()
            ) { }
        }
    }

    private suspend fun <T> get(path: String, parse: (String) -> T): HaCall<T> {
        val creds = credentials.current() ?: return HaCall.Failed(WidgetBlocker.SIGNED_OUT)
        return withContext(Dispatchers.IO) {
            request(
                Request.Builder()
                    .url("${creds.baseUrl.trimEnd('/')}/api/$path")
                    .header("Authorization", "Bearer ${creds.token}")
                    .build()
            ) { body -> parse(body) }
        }
    }

    private fun <T> request(request: Request, parse: (String) -> T): HaCall<T> = try {
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful ->
                    try {
                        HaCall.Ok(parse(response.body?.string().orEmpty()))
                    } catch (e: Exception) {
                        // A 200 we can't read means HA answered with something unexpected — a proxy
                        // error page, a shape change. Not reachable in any useful sense.
                        HaCall.Failed(WidgetBlocker.UNREACHABLE)
                    }
                response.code == 401 || response.code == 403 ->
                    HaCall.Failed(WidgetBlocker.UNAUTHORIZED)
                response.code == 404 ->
                    HaCall.Failed(WidgetBlocker.ENTITY_MISSING)
                else ->
                    HaCall.Failed(WidgetBlocker.UNREACHABLE)
            }
        }
    } catch (e: IOException) {
        // On this setup an IOException is nearly always "the tailnet isn't up".
        HaCall.Failed(WidgetBlocker.UNREACHABLE)
    }

    private fun serviceBody(entityId: String, extra: Map<String, Any?>): JsonObject = buildJsonObject {
        put("entity_id", entityId)
        extra.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                is String -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
