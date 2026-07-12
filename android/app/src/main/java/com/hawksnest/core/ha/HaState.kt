package com.hawksnest.core.ha

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionStatus { DEMO, CONNECTING, CONNECTED, ERROR }

/**
 * The in-memory entity store — the Kotlin analogue of the web's Zustand `entityStore`. Hawksnest
 * is **live-first with NO persistent entity cache** (HA state must never be shown stale), so this
 * is a singleton holder of [StateFlow]s hydrated only while a [Source] is running.
 *
 * Mirrors `src/store/entityStore.ts`.
 */
@Singleton
class HaState @Inject constructor() {
    private val _entities = MutableStateFlow<Map<String, HassEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HassEntity>> = _entities.asStateFlow()

    private val _areas = MutableStateFlow<AreaRegistry>(emptyMap())
    val areas: StateFlow<AreaRegistry> = _areas.asStateFlow()

    /** entity_id → "config"/"diagnostic" for entities the main list + History hide (from the registry). */
    private val _entityCategories = MutableStateFlow<EntityCategories>(emptyMap())
    val entityCategories: StateFlow<EntityCategories> = _entityCategories.asStateFlow()

    /** Device index (device → entities) backing the per-device diagnostics view + security dedupe. */
    private val _devices = MutableStateFlow(DeviceIndex())
    val devices: StateFlow<DeviceIndex> = _devices.asStateFlow()

    /** Entity ids owned by the Z-Wave JS integration (for controller-liveness detection). */
    private val _zwaveEntityIds = MutableStateFlow<List<String>>(emptyList())
    val zwaveEntityIds: StateFlow<List<String>> = _zwaveEntityIds.asStateFlow()

    /** entity_id → integration platform ("ring", "mqtt", …) — feeds the ring/ring-mqtt dedupe. */
    private val _entityPlatforms = MutableStateFlow<Map<String, String>>(emptyMap())
    val entityPlatforms: StateFlow<Map<String, String>> = _entityPlatforms.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.CONNECTING)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Connected HA origin for resolving camera image URLs ("" in demo mode). */
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    /** Epoch ms of the last entity update — drives the "as of HH:MM" staleness read-out. */
    private val _lastUpdateMs = MutableStateFlow(0L)
    val lastUpdateMs: StateFlow<Long> = _lastUpdateMs.asStateFlow()

    /** Replace the whole snapshot (initial load / full re-sync). */
    fun setSnapshot(entities: Map<String, HassEntity>, areas: AreaRegistry) {
        _entities.value = entities
        _areas.value = areas
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    /** Replace just the entity map (a live state push); leaves areas intact. */
    fun setEntities(entities: Map<String, HassEntity>) {
        _entities.value = entities
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    fun setAreas(areas: AreaRegistry) { _areas.value = areas }

    fun setEntityCategories(categories: EntityCategories) { _entityCategories.value = categories }

    fun setDevices(devices: DeviceIndex) { _devices.value = devices }

    fun setZWaveEntityIds(ids: List<String>) { _zwaveEntityIds.value = ids }

    fun setEntityPlatforms(platforms: Map<String, String>) { _entityPlatforms.value = platforms }

    /** Merge a batch of entity updates (live state changes). */
    fun upsertEntities(list: List<HassEntity>) {
        if (list.isEmpty()) return
        _entities.value = _entities.value.toMutableMap().apply {
            for (e in list) this[e.entityId] = e
        }
        _lastUpdateMs.value = System.currentTimeMillis()
    }

    fun setStatus(status: ConnectionStatus, error: String? = null) {
        _status.value = status
        _error.value = error
    }

    fun setBaseUrl(baseUrl: String) { _baseUrl.value = baseUrl }
}
