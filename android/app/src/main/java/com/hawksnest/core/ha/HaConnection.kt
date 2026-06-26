package com.hawksnest.core.ha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when HA rejects the access token (`auth_invalid`) — a terminal error (don't retry). */
class HaAuthException(message: String) : Exception(message)

/** Thrown to fail in-flight requests when the socket closes. */
class HaClosedException : Exception("Connection to Home Assistant closed")

/**
 * One Home Assistant WebSocket connection: the auth handshake + request/response correlation
 * (the `home-assistant-js-websocket` / `sendMessagePromise` analogue). Frames are sent as compact
 * JSON (`JsonObject.toString()`); incoming frames are routed to pending requests, the entities
 * subscription, or the auth gate.
 */
class HaConnection(
    private val client: OkHttpClient,
    private val json: Json,
    private val wsUrl: String,
    private val token: String,
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val authGate = CompletableDeferred<Unit>()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var entitiesSink: ((JsonObject) -> Unit)? = null
    @Volatile private var closedCb: (() -> Unit)? = null

    fun onEntitiesEvent(sink: (JsonObject) -> Unit) { entitiesSink = sink }
    fun onClosed(cb: () -> Unit) { closedCb = cb }

    /** Open the socket and suspend until `auth_ok`; throws [HaAuthException] on `auth_invalid`. */
    suspend fun connect() {
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        authGate.await()
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            handle(frame)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail()
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
    }

    private fun handle(frame: JsonObject) {
        when (HaMessages.frameType(frame)) {
            HaMessages.TYPE_AUTH_REQUIRED -> ws?.send(HaMessages.auth(token).toString())
            HaMessages.TYPE_AUTH_OK -> authGate.complete(Unit)
            HaMessages.TYPE_AUTH_INVALID -> authGate.completeExceptionally(
                HaAuthException(HaMessages.errorMessage(frame) ?: "Invalid access token"),
            )
            HaMessages.TYPE_RESULT -> HaMessages.frameId(frame)?.let { pending.remove(it)?.complete(frame) }
            HaMessages.TYPE_EVENT -> (frame["event"] as? JsonObject)?.let { entitiesSink?.invoke(it) }
        }
    }

    /** Send a command and await its `result` frame. */
    suspend fun request(type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val id = nextId.getAndIncrement()
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        ws?.send(HaMessages.command(id, type, build).toString())
        return def.await()
    }

    /** Fire-and-forget subscribe; entity events arrive via [onEntitiesEvent]. */
    fun subscribeEntities() {
        ws?.send(HaMessages.command(nextId.getAndIncrement(), "subscribe_entities").toString())
    }

    suspend fun callService(domain: String, service: String, entityId: String?) {
        val id = nextId.getAndIncrement()
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        ws?.send(HaMessages.callService(id, domain, service, entityId).toString())
        def.await()
    }

    fun ping() {
        ws?.send(HaMessages.command(nextId.getAndIncrement(), "ping").toString())
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
        fail()
    }

    private fun fail() {
        if (!authGate.isCompleted) authGate.completeExceptionally(HaClosedException())
        pending.values.forEach { it.completeExceptionally(HaClosedException()) }
        pending.clear()
        closedCb?.invoke()
    }
}
