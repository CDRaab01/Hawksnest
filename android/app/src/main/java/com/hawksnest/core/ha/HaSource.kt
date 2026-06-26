package com.hawksnest.core.ha

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.time.Instant
import kotlin.coroutines.coroutineContext

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

    override suspend fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
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

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (coroutineContext.isActive) {
            state.setBaseUrl(baseUrl)
            state.setStatus(ConnectionStatus.CONNECTING)
            entities = emptyMap()
            val c = HaConnection(client, json, wsUrl(baseUrl), token)
            conn = c
            val closed = CompletableDeferred<Unit>()
            c.onClosed { if (!closed.isCompleted) closed.complete(Unit) }
            c.onEntitiesEvent { ev ->
                entities = applyEntitiesEvent(entities, ev)
                state.setEntities(entities)
            }
            try {
                c.connect() // suspends until auth_ok; throws HaAuthException on bad token
                c.subscribeEntities()
                loadAreas(c)
                state.setStatus(ConnectionStatus.CONNECTED)
                backoff = 1_000L
                while (coroutineContext.isActive && !closed.isCompleted) {
                    delay(25_000)
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
            }
            if (!coroutineContext.isActive) break
            state.setStatus(ConnectionStatus.CONNECTING, "Reconnecting…")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun loadAreas(c: HaConnection) {
        try {
            val areas = c.request("config/area_registry/list")["result"] as? JsonArray ?: return
            val entitiesReg = c.request("config/entity_registry/list")["result"] as? JsonArray ?: return
            val devices = c.request("config/device_registry/list")["result"] as? JsonArray ?: return
            state.setAreas(buildAreaRegistry(areas, entitiesReg, devices))
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
