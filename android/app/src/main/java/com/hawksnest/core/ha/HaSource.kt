package com.hawksnest.core.ha

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.dedupeRingMqtt
import com.hawksnest.core.net.ReachabilityProbe
import com.hawksnest.core.logic.FRIGATE_BASE
import com.hawksnest.core.logic.LogEvent
import com.hawksnest.core.logic.normalizeLogbook
import com.hawksnest.core.logic.normalizeFrigateEvents
import com.hawksnest.core.logic.recordingUrlAt as buildRecordingUrl
import com.hawksnest.core.logic.eventClipUrl as buildEventClipUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** How long we give HA to produce an HLS stream URL before stepping down (see [HaSource.streamUrl]). */
private const val STREAM_URL_TIMEOUT_MS = 15_000L

/**
 * The live Home Assistant source over the WebSocket. Mirrors `src/store/haSource.ts`: connect +
 * auth, `subscribe_entities` (compressed deltas applied via [applyEntitiesEvent]), pull the three
 * registries once per connect (→ [buildAreaRegistry]), and forward **non-optimistic** service
 * calls. Reconnects with exponential backoff; a bad token is terminal (no retry).
 */
class HaSource(
    private val client: OkHttpClient,
    private val json: Json,
    private val state: HaState,
    private val scope: CoroutineScope,
    private val baseUrl: String,
    private val token: String,
) : Source {

    @Volatile private var job: Job? = null
    @Volatile private var conn: HaConnection? = null
    @Volatile private var entities: Map<String, HassEntity> = emptyMap()

    /** Releases the backoff wait early when the Offline screen's "Retry now" is tapped. */
    private val retrySignal = RetrySignal()

    /** Shared bounded probe for the per-backoff-cycle reachability hint (see [runLoop]). */
    private val probe = ReachabilityProbe.from(client)

    /** True while a reachability probe is in flight, so cheap early backoffs don't stack them. */
    @Volatile private var probing = false

    override suspend fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    override fun retryNow() {
        retrySignal.signal()
    }

    override fun stop() {
        job?.cancel()
        job = null
        conn?.close()
        conn = null
    }

    override suspend fun callService(domain: String, service: String, data: ServiceData) {
        val c = conn ?: throw IllegalStateException("Not connected to Home Assistant.")
        c.callService(domain, service, data.entityId, data.extra)
    }

    override suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> {
        val c = conn ?: throw IllegalStateException("Not connected to Home Assistant.")
        val startIso = Instant.now().minusSeconds(hours * 3600L).toString()
        val frame = c.request("history/history_during_period") {
            put("start_time", startIso)
            putJsonArray("entity_ids") { add(entityId) }
            put("minimal_response", true)
            put("no_attributes", true)
        }
        val result = frame["result"] as? JsonObject ?: return emptyList()
        return parseHistory(result, entityId)
    }

    override suspend fun fetchAttributeHistory(
        entityId: String,
        startMs: Long,
        endMs: Long,
        attr: String,
    ): List<Pair<Long, String>> {
        val c = conn ?: throw IllegalStateException("Not connected to Home Assistant.")
        // Keep attributes and ALL changes: the ring event selector's state ("Motion 1") never
        // changes, but its eventId attribute does per event — that's what we're after.
        val frame = c.request("history/history_during_period") {
            put("start_time", Instant.ofEpochMilli(startMs).toString())
            put("end_time", Instant.ofEpochMilli(endMs).toString())
            putJsonArray("entity_ids") { add(entityId) }
            put("minimal_response", false)
            put("no_attributes", false)
            put("significant_changes_only", false)
        }
        val result = frame["result"] as? JsonObject ?: return emptyList()
        return parseAttributeHistory(result, entityId, attr)
    }

    override suspend fun fetchLogbook(startMs: Long, endMs: Long, entityIds: List<String>?): List<LogEvent> {
        val c = conn ?: throw IllegalStateException("Not connected to Home Assistant.")
        val frame = c.request("logbook/get_events") {
            put("start_time", Instant.ofEpochMilli(startMs).toString())
            put("end_time", Instant.ofEpochMilli(endMs).toString())
            if (!entityIds.isNullOrEmpty()) putJsonArray("entity_ids") { entityIds.forEach { add(it) } }
        }
        val result = frame["result"] as? JsonArray ?: return emptyList()
        return normalizeLogbook(result.mapNotNull { it as? JsonObject })
    }

    /**
     * Ask HA for an on-demand HLS stream URL (`camera/stream`). HA returns a signed, root-relative
     * playlist path served under the same `/api` the nginx pod proxies; resolved against the origin
     * like camera snapshots. Null on any failure so the player falls back to MJPEG/snapshot.
     *
     * Bounded at 15s: HA answers only once the camera's stream pipeline is up, and a battery Ring
     * camera being woken by go2rtc can block this for the better part of a minute. Null already
     * means "step down the transport ladder", so a timeout degrades instead of hanging the player.
     */
    override suspend fun streamUrl(entityId: String): String? {
        val c = conn ?: return null
        return try {
            withTimeoutOrNull(STREAM_URL_TIMEOUT_MS) {
                val frame = c.request("camera/stream") {
                    put("entity_id", entityId)
                    put("format", "hls")
                }
                val url = (frame["result"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                url?.let { withBase(it, baseUrl) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read recorded events for a Frigate camera over `[startMs, endMs]`. Frigate's HA integration
     * exposes its API under `/api/frigate/…` (same-origin through nginx). Degrades to [] if Frigate
     * isn't installed yet or the request fails, so the timeline renders empty rather than throwing.
     */
    override suspend fun fetchCameraEvents(camera: String, startMs: Long, endMs: Long): List<CameraEvent> {
        val base = withBase(FRIGATE_BASE, baseUrl)
        val url = "$base/events?camera=$camera&after=${startMs / 1000}&before=${endMs / 1000}&limit=500"
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use emptyList()
                    val body = res.body?.string() ?: return@use emptyList()
                    val arr = json.parseToJsonElement(body) as? JsonArray ?: return@use emptyList()
                    normalizeFrigateEvents(arr.mapNotNull { it as? JsonObject }, base)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Automation CRUD over HA's REST Config API (`/api/config/automation/config/<id>`). Mirrors the
     * web `haSource.ts`: GET (404 → null), POST the config body, DELETE. HA reloads automations on
     * write, so the changed `automation.*` entity flows back over the WebSocket and the list updates.
     * Writes need an admin token — a 401/403 surfaces as a friendly message.
     */
    override suspend fun getAutomationConfig(id: String): JsonObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(automationConfigUrl(id))
            .header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { res ->
            if (res.code == 404) return@use null
            if (!res.isSuccessful) throw configApiError(res.code)
            val body = res.body?.string() ?: return@use null
            json.parseToJsonElement(body) as? JsonObject
        }
    }

    override suspend fun saveAutomationConfig(config: JsonObject) {
        val id = (config["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("Automation has no id.")
        withContext(Dispatchers.IO) {
            val body = config.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(automationConfigUrl(id)).post(body)
                .header("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw configApiError(res.code)
            }
        }
    }

    override suspend fun deleteAutomationConfig(id: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(automationConfigUrl(id)).delete()
                .header("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw configApiError(res.code)
            }
        }
    }

    private fun automationConfigUrl(id: String): String =
        "${baseUrl.trimEnd('/')}/api/config/automation/config/$id"

    private fun configApiError(code: Int): Exception = when (code) {
        401, 403 -> IllegalStateException("Editing automations needs a Home Assistant admin token.")
        else -> IllegalStateException("Home Assistant couldn't save the automation (HTTP $code).")
    }

    override fun recordingUrlAt(camera: String, startMs: Long, endMs: Long): String =
        buildRecordingUrl(camera, startMs, endMs, withBase(FRIGATE_BASE, baseUrl))

    override fun eventClipUrl(eventId: String): String =
        buildEventClipUrl(eventId, withBase(FRIGATE_BASE, baseUrl))

    override fun supportsWebRtc(): Boolean = true

    /**
     * Negotiate a WebRTC live session over HA's `camera/webrtc/offer` (a subscribe-style command
     * served by go2rtc — ring-mqtt's streaming backend). HA streams the session id, SDP answer, and
     * trickle ICE candidates back as `event` frames; we map each to a [WebRtcSignal] for the player.
     */
    override suspend fun webrtcOffer(
        entityId: String,
        offerSdp: String,
        onSignal: (WebRtcSignal) -> Unit,
    ): WebRtcHandle? {
        val c = conn ?: return null
        val id = c.subscribe(
            type = "camera/webrtc/offer",
            build = {
                put("entity_id", entityId)
                put("offer", offerSdp)
            },
            onEvent = { event -> parseWebRtcSignal(event)?.let(onSignal) },
        )
        return WebRtcHandle { c.unsubscribe(id) }
    }

    override suspend fun webrtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
        val c = conn ?: return
        c.request("camera/webrtc/candidate") {
            // `entity_id` is required by HA alongside the session id; without it HA
            // rejects the candidate and ICE never completes (live view goes stale).
            put("entity_id", entityId)
            put("session_id", sessionId)
            putJsonObject("candidate") {
                put("candidate", candidate)
                if (sdpMid != null) put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            }
        }
    }

    /** Map one `camera/webrtc/offer` event payload to a [WebRtcSignal] (or null to ignore). */
    private fun parseWebRtcSignal(event: JsonObject): WebRtcSignal? =
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "session" -> event["session_id"]?.jsonPrimitive?.contentOrNull?.let { WebRtcSignal.Session(it) }
            "answer" -> event["answer"]?.jsonPrimitive?.contentOrNull?.let { WebRtcSignal.Answer(it) }
            "candidate" -> {
                // HA sends the candidate as an object (`{candidate, sdpMid, sdpMLineIndex}`) or, on
                // older builds, a bare SDP string.
                when (val cand = event["candidate"]) {
                    is JsonObject -> cand["candidate"]?.jsonPrimitive?.contentOrNull?.let {
                        WebRtcSignal.Candidate(
                            candidate = it,
                            sdpMid = cand["sdpMid"]?.jsonPrimitive?.contentOrNull,
                            sdpMLineIndex = cand["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }
                    is JsonPrimitive -> cand.contentOrNull?.let { WebRtcSignal.Candidate(it, null, 0) }
                    else -> null
                }
            }
            "error" -> WebRtcSignal.Error
            else -> null
        }

    /** Prefix a root-relative HA/Frigate path with the connected origin; absolute/empty left alone. */
    private fun withBase(path: String, base: String): String =
        if (base.isEmpty() || !path.startsWith("/")) path else base.trimEnd('/') + path

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (coroutineContext.isActive) {
            state.setBaseUrl(baseUrl)
            state.setStatus(ConnectionStatus.CONNECTING)
            entities = emptyMap()
            val c = HaConnection(client, json, wsUrl(baseUrl), token)
            val closed = CompletableDeferred<Unit>()
            c.onClosed { if (!closed.isCompleted) closed.complete(Unit) }
            c.onEntitiesEvent { ev ->
                entities = applyEntitiesEvent(entities, ev)
                // Central dedupe: every consumer (Home, Devices, cameras) sees one
                // entity per physical device even while Ring + ring-mqtt are both live.
                state.setEntities(dedupeRingMqtt(entities, state.entityPlatforms.value))
            }
            try {
                c.connect() // suspends until auth_ok; throws HaAuthException on bad token
                // Expose the connection to other callers (History/cameras) only after auth_ok, so a
                // request issued mid-handshake can't race a raw frame onto the socket. The `finally`
                // still closes `c` if start/stop cancels us while connecting.
                conn = c
                c.subscribeEntities()
                loadAreas(c)
                state.setStatus(ConnectionStatus.CONNECTED)
                backoff = 1_000L
                while (coroutineContext.isActive && !closed.isCompleted) {
                    // Race the ping interval against the close signal so a dead socket flips the
                    // status (and masks lock/alarm state) immediately, not at the next 25s tick.
                    withTimeoutOrNull(25_000) { closed.await() }
                    if (!closed.isCompleted) c.ping()
                }
            } catch (e: HaAuthException) {
                state.setStatus(ConnectionStatus.ERROR, e.message ?: "Invalid access token")
                c.close()
                conn = null
                return // terminal — don't retry a bad token
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // unreachable / dropped — fall through to backoff + reconnect
            } finally {
                c.close()
                // Stop handing this (now-closed) connection to History/camera callers; until the next
                // socket authenticates they get a clean "Not connected" instead of awaiting forever.
                if (conn === c) conn = null
            }
            if (!coroutineContext.isActive) break
            state.setStatus(ConnectionStatus.CONNECTING, "Reconnecting…")
            // One passive reachability probe per backoff cycle (never continuous): fire-and-forget
            // so its bounded 8s can't delay the retry, and skip if the last one is still running.
            if (!probing) {
                probing = true
                scope.launch {
                    try {
                        state.setHostReachable(probe.isReachable(baseUrl))
                    } finally {
                        probing = false
                    }
                }
            }
            // Skippable backoff: publish when the next attempt fires (drives the Offline screen's
            // "Retrying in Ns"), then wait — released early by retryNow(). Drain first so a tap
            // that landed while a connect attempt was already in flight can't skip this wait.
            retrySignal.drain()
            state.setNextRetryAt(System.currentTimeMillis() + backoff)
            retrySignal.awaitOrTimeout(backoff)
            state.setNextRetryAt(null)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun loadAreas(c: HaConnection) {
        try {
            val areas = c.request("config/area_registry/list")["result"] as? JsonArray ?: return
            val entitiesReg = c.request("config/entity_registry/list")["result"] as? JsonArray ?: return
            val devices = c.request("config/device_registry/list")["result"] as? JsonArray ?: return
            state.setAreas(buildAreaRegistry(areas, entitiesReg, devices))
            state.setEntityCategories(buildEntityCategories(entitiesReg))
            state.setDevices(buildDeviceIndex(areas, entitiesReg, devices))
            state.setZWaveEntityIds(buildZWaveEntityIds(entitiesReg))
            state.setEntityPlatforms(buildEntityPlatforms(entitiesReg))
            // Platforms may arrive after the first entity push — re-filter what's shown.
            state.setEntities(dedupeRingMqtt(entities, state.entityPlatforms.value))
        } catch (_: Exception) {
            // non-fatal: without a registry, entities just group under "Unassigned"
        }
    }

    /** `http(s)://host[:port]` → `ws(s)://host[:port]/api/websocket`. */
    private fun wsUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        val swapped = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "ws://$trimmed"
        }
        return "$swapped/api/websocket"
    }
}
