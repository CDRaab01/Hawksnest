package com.hawksnest.core.ha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
    /** Subscribe-style commands (e.g. `camera/webrtc/offer`): id → event sink for its `event` frames. */
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
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
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ws = null; fail() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { ws = null; fail() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ws = null; fail() }
    }

    private fun handle(frame: JsonObject) {
        when (HaMessages.frameType(frame)) {
            HaMessages.TYPE_AUTH_REQUIRED -> ws?.send(HaMessages.auth(token).toString())
            HaMessages.TYPE_AUTH_OK -> authGate.complete(Unit)
            HaMessages.TYPE_AUTH_INVALID -> authGate.completeExceptionally(
                HaAuthException(HaMessages.errorMessage(frame) ?: "Invalid access token"),
            )
            HaMessages.TYPE_RESULT -> HaMessages.frameId(frame)?.let { pending.remove(it)?.complete(frame) }
            HaMessages.TYPE_EVENT -> {
                val event = frame["event"] as? JsonObject
                val sub = HaMessages.frameId(frame)?.let { subscriptions[it] }
                // Route to the matching subscribe-style command; otherwise it's the entity stream.
                if (sub != null) event?.let(sub) else event?.let { entitiesSink?.invoke(it) }
            }
        }
    }

    /**
     * Send a command and await its `result` frame. Suspends on [authGate] first: HA's WebSocket
     * rejects any non-`auth` frame during the handshake ("Auth message incorrectly formatted…")
     * and closes the socket, so a command must never go out before `auth_ok`.
     */
    suspend fun request(type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        authGate.await()
        val id = nextId.getAndIncrement()
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        val sock = ws
        // If the socket is gone or the send fails, unblock the caller instead of suspending forever.
        if (sock == null || !sock.send(HaMessages.command(id, type, build).toString())) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            throw HaClosedException()
        }
        // Close the race where fail() ran between authGate.await() and the insert above.
        if (ws == null) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            throw HaClosedException()
        }
        return def.await()
    }

    /**
     * Start a subscribe-style command (HA streams `event` frames until unsubscribed). [onEvent]
     * receives each event payload; the returned id is passed to [unsubscribe] to tear it down.
     * Gated on auth like [request]. Suspends until HA acks the subscription with its `result` frame.
     */
    suspend fun subscribe(
        type: String,
        build: JsonObjectBuilder.() -> Unit = {},
        onEvent: (JsonObject) -> Unit,
    ): Int {
        authGate.await()
        val id = nextId.getAndIncrement()
        subscriptions[id] = onEvent
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        val sock = ws
        // If the socket is gone or the send fails, unblock the caller instead of suspending forever.
        if (sock == null || !sock.send(HaMessages.command(id, type, build).toString())) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            subscriptions.remove(id)
            throw HaClosedException()
        }
        // Close the race where fail() ran between authGate.await() and the insert above.
        if (ws == null) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            subscriptions.remove(id)
            throw HaClosedException()
        }
        def.await() // subscription acknowledged
        return id
    }

    /** Stop routing events for a subscription and best-effort tell HA to unsubscribe. */
    fun unsubscribe(id: Int) {
        subscriptions.remove(id) ?: return
        if (!authGate.isCompleted) return
        ws?.send(
            HaMessages.command(nextId.getAndIncrement(), "unsubscribe_events") { put("subscription", id) }
                .toString(),
        )
    }

    /** Fire-and-forget subscribe; entity events arrive via [onEntitiesEvent]. Post-auth only. */
    fun subscribeEntities() {
        if (!authGate.isCompleted) return
        ws?.send(HaMessages.command(nextId.getAndIncrement(), "subscribe_entities").toString())
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        serviceData: Map<String, Any?> = emptyMap(),
    ) {
        authGate.await()
        val id = nextId.getAndIncrement()
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        val sock = ws
        // If the socket is gone or the send fails, unblock the caller instead of suspending forever.
        if (sock == null || !sock.send(HaMessages.callService(id, domain, service, entityId, serviceData).toString())) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            throw HaClosedException()
        }
        // Close the race where fail() ran between authGate.await() and the insert above.
        if (ws == null) {
            pending.remove(id)?.completeExceptionally(HaClosedException())
            throw HaClosedException()
        }
        def.await()
    }

    fun ping() {
        if (!authGate.isCompleted) return
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
        subscriptions.clear()
        closedCb?.invoke()
    }
}
