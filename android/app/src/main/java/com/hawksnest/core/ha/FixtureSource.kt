package com.hawksnest.core.ha

import com.hawksnest.core.automations.slugify
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.DEMO_CLIP_URI
import com.hawksnest.core.logic.LogEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Demo data source — no live HA. Loads the fixtures into [HaState] and simulates service calls
 * locally (the live HA source forwards them and reconciles via the state echo instead). Behind the
 * same [Source] interface as the live source, so no screen changes when we swap.
 */
class FixtureSource @Inject constructor(private val state: HaState) : Source {

    /** In-memory automation configs (id → config) + their synthetic entity ids, mirroring HA. */
    private val automationConfigs = mutableMapOf<String, JsonObject>()
    private val automationEntityIds = mutableMapOf<String, String>()

    override suspend fun start() {
        state.setBaseUrl("")
        state.setSnapshot(fixtureEntities.associateBy { it.entityId }, fixtureAreaRegistry)
        // Seed the editable configs behind the demo automation entities so the builder round-trips.
        automationConfigs.clear()
        automationConfigs.putAll(fixtureAutomationConfigs)
        automationEntityIds.clear()
        fixtureEntities.filter { domainOf(it.entityId) == "automation" }.forEach { e ->
            e.stringAttr("id")?.let { automationEntityIds[it] = e.entityId }
        }
        state.setStatus(ConnectionStatus.DEMO)
    }

    override fun stop() { /* nothing to tear down */ }

    override suspend fun callService(domain: String, service: String, data: ServiceData) {
        val id = data.entityId ?: return
        val current = state.entities.value[id] ?: return
        // Keep the current state when a service only changes attributes (set_percentage, etc.).
        val newState = simulatedState(domain, service, data) ?: current.state
        // Reflect extra service data so the demo's sliders/setpoints move.
        val attrs = current.attributes.toMutableMap()
        data.extra.forEach { (k, v) ->
            if (k == "brightness_pct") {
                (v as? Number)?.let { attrs["brightness"] = anyToJsonElement((it.toDouble() * 2.55).toInt()) }
            } else {
                attrs[k] = anyToJsonElement(v)
            }
        }
        // Mirror the live source's non-optimistic echo: we mutate the store as HA would.
        state.upsertEntities(listOf(current.copy(state = newState, attributes = JsonObject(attrs))))
    }

    /**
     * Synthesize a plausible series so the demo's entity-detail chart renders. Numeric entities get
     * a gentle deterministic wave around their current value; discrete entities hold their state.
     * The last sample matches the live state so the chart's latest point agrees with the card.
     */
    override suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> {
        val current = state.entities.value[entityId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val n = 24
        val stepMs = hours * 3600_000L / n
        val base = current.state.toFloatOrNull()
        return (0 until n).map { i ->
            val t = now - (n - 1 - i) * stepMs
            val s = when {
                i == n - 1 -> current.state // anchor the latest point to the live state
                base != null -> {
                    val wave = base * (1f + 0.06f * kotlin.math.sin(i.toFloat()))
                    ((wave * 10).roundToInt() / 10f).toString()
                }
                else -> current.state
            }
            HistoryPoint(t, s)
        }
    }

    /**
     * Synthesize a short demo logbook so the History tab isn't empty without a live HA — one event
     * per entity (its current state), spaced a few minutes apart and clamped to the window. Real
     * timestamps come from HA in the live source.
     */
    override suspend fun fetchLogbook(startMs: Long, endMs: Long, entityIds: List<String>?): List<LogEvent> {
        val now = System.currentTimeMillis().coerceAtMost(endMs)
        val entities = state.entities.value.values
            .filter { entityIds == null || it.entityId in entityIds }
            .take(20)
        return entities.mapIndexedNotNull { i, e ->
            val t = now - i * 7 * 60_000L
            if (t < startMs) return@mapIndexedNotNull null
            LogEvent(
                timeMs = t,
                name = e.friendlyName() ?: e.entityId,
                message = "changed to ${e.state}",
                entityId = e.entityId,
                domain = domainOf(e.entityId),
                state = e.state,
            )
        }
    }

    override suspend fun getAutomationConfig(id: String): JsonObject? = automationConfigs[id]

    /** Save in memory and upsert a synthetic `automation.<slug>` entity, as HA would after a reload. */
    override suspend fun saveAutomationConfig(config: JsonObject) {
        val id = (config["id"] as? JsonPrimitive)?.contentOrNull ?: return
        automationConfigs[id] = config
        val alias = (config["alias"] as? JsonPrimitive)?.contentOrNull ?: id
        // Reuse the existing entity id on edit so an alias change doesn't orphan the old entity.
        val entityId = automationEntityIds.getOrPut(id) { "automation.${slugify(alias)}" }
        state.upsertEntities(
            listOf(
                HassEntity(
                    entityId = entityId,
                    state = "on",
                    attributes = buildJsonObject {
                        put("friendly_name", alias)
                        put("id", id)
                    },
                ),
            ),
        )
    }

    override suspend fun deleteAutomationConfig(id: String) {
        automationConfigs.remove(id)
        val entityId = automationEntityIds.remove(id) ?: return
        state.setEntities(state.entities.value.toMutableMap().apply { remove(entityId) })
    }

    /** Demo "live" feed: loop the bundled clip for any camera entity. */
    override suspend fun streamUrl(entityId: String): String? =
        if (domainOf(entityId) == "camera") DEMO_CLIP_URI else null

    /** Demo: every seek plays the same bundled clip (no real recordings). */
    override fun recordingUrlAt(camera: String, startMs: Long, endMs: Long): String = DEMO_CLIP_URI

    override fun eventClipUrl(eventId: String): String = DEMO_CLIP_URI

    /**
     * Synthesize a believable 24h spread of recorded camera events so the demo timeline scrubber is
     * populated without Frigate. Events land on a steady cadence, vary their label/duration, and are
     * returned oldest-first. Mirrors `fixtureSource.synthCameraEvents` on the web.
     */
    override suspend fun fetchCameraEvents(camera: String, startMs: Long, endMs: Long): List<CameraEvent> {
        val stepMs = 37 * 60_000L // ~one event every 37 minutes
        val labels = listOf("person", "motion", "car", "motion", "dog", "person")
        val out = mutableListOf<CameraEvent>()
        var slot = 0
        var t = startMs
        while (t <= endMs) {
            val durationMs = 20_000L + (slot % 5) * 15_000L // 20s–80s
            out.add(
                CameraEvent(
                    id = "demo-$camera-$slot",
                    camera = camera,
                    label = labels[slot % labels.size],
                    startMs = t,
                    endMs = minOf(endMs, t + durationMs),
                    hasClip = true,
                    hasSnapshot = false,
                    thumbnailUrl = null,
                    snapshotUrl = null,
                ),
            )
            t += stepMs
            slot++
        }
        return out
    }

    /** The resulting state for a simulated `domain.service`, or null to keep the current state. */
    private fun simulatedState(domain: String, service: String, data: ServiceData): String? =
        when (service) {
            "lock" -> "locked"
            "unlock" -> "unlocked"
            "alarm_disarm" -> "disarmed"
            "alarm_arm_home" -> "armed_home"
            "alarm_arm_away" -> "armed_away"
            "alarm_arm_night" -> "armed_night"
            "open_cover" -> "open"
            "close_cover" -> "closed"
            "turn_on" -> "on"
            "turn_off" -> "off"
            "media_play_pause" -> if (state.entities.value[data.entityId]?.state == "playing") "paused" else "playing"
            else -> null
        }
}
