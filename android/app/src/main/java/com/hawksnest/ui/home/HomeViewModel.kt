package com.hawksnest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.ha.DeviceIndex
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.AlarmView
import com.hawksnest.core.logic.DoorbellPress
import com.hawksnest.core.logic.activeDoorbellPress
import com.hawksnest.core.logic.alarmView
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.isCameraLive
import com.hawksnest.core.logic.resolveCameras
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.securityReadout
import com.hawksnest.core.logic.snapshotUrl
import com.hawksnest.core.logic.streamUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject

/**
 * Parse an entity's `last_changed` to epoch ms for the camera age badge. Over the
 * compressed websocket HA sends it as epoch seconds (a numeric string); the REST
 * shape is ISO-8601 — accept both, null when absent/unparseable.
 */
private fun lastChangedMs(raw: String?): Long? {
    if (raw.isNullOrEmpty()) return null
    raw.toDoubleOrNull()?.let { return (it * 1000).toLong() }
    return runCatching { Instant.parse(raw).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()
}

data class CameraUi(
    /** Stable logical id (`camera.<base>`) — ring-mqtt's split entities collapse to one. */
    val id: String,
    /** The entity to stream live from (ring-mqtt's `_live`, or the camera itself). */
    val entityId: String,
    val name: String,
    val live: Boolean,
    /** Snapshot's last-change time (epoch ms) for the Ring-style age badge, or null. */
    val lastChangedMs: Long? = null,
    /** Signed snapshot URL resolved against the HA origin (null in demo / when down). */
    val snapshotUrl: String? = null,
    /** Derived MJPEG live-stream URL (used as a fallback in the lightbox). */
    val streamUrl: String? = null,
    /** ring-mqtt event selector (`select.<base>_event_select`), or null. */
    val eventSelectId: String? = null,
    /** ring-mqtt recorded-event playback stream (`camera.<base>_event`), or null. */
    val eventStreamId: String? = null,
    /** Doorbell press sensor (`binary_sensor.<base>_ding`), or null. */
    val dingId: String? = null,
    /** ring-mqtt siren switch (`switch.<base>_siren`) on siren-capable cameras, or null. */
    val sirenSwitchId: String? = null,
)

data class HomeUi(
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val error: String? = null,
    val alarm: AlarmView? = null,
    val alarmEntityId: String? = null,
    val alarmRawState: String? = null,
    /** Plain-language security read-out: "All doors locked" or "Front Door open · …". */
    val securitySummary: String = "",
    val secureAllClear: Boolean = true,
    val offlineLabel: String? = null,
    val cameras: List<CameraUi> = emptyList(),
    val liveCameraCount: Int = 0,
    /** The most recent active doorbell ring, if any (drives the doorbell banner). */
    val doorbell: DoorbellPress? = null,
    val roomCount: Int = 0,
    val roomsPreview: String = "",
    /** Triggered life-safety sensors (smoke/CO/gas/leak), surfaced regardless of armed state. */
    val lifeSafetyAlerts: List<String> = emptyList(),
    val lifeSafetyMonitored: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val uiState: StateFlow<HomeUi> = combine(
        state.entities, state.areas, state.status, state.error, state.baseUrl, state.devices,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        buildUi(
            values[0] as Map<String, HassEntity>,
            values[1] as Map<String, String>,
            values[2] as ConnectionStatus,
            values[3] as String?,
            values[4] as String,
            values[5] as DeviceIndex,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        viewModelScope.launch { connection.start() }
    }

    /** Arm/disarm. Non-optimistic — the store reconciles from the source echo. Disarm sends no
     *  code: the in-app PIN keypad was removed, so a panel that enforces `code_format` must allow
     *  codeless disarm from a trusted device (HA `code_arm_required: false` / no code on disarm). */
    fun arm(service: String) {
        val id = uiState.value.alarmEntityId ?: return
        viewModelScope.launch {
            connection.callService("alarm_control_panel", service, ServiceData(entityId = id))
        }
    }

    private fun buildUi(
        entities: Map<String, HassEntity>,
        areas: Map<String, String>,
        status: ConnectionStatus,
        error: String?,
        baseUrl: String,
        devices: DeviceIndex,
    ): HomeUi {
        val all = entities.values.toList()

        // Prefer a panel that's actually reporting over one stuck unavailable/unknown (a Ring Alarm
        // base station that briefly drops out shouldn't make the hero read "No alarm panel").
        val panels = all.filter { it.entityId.startsWith("alarm_control_panel.") }
        val alarmEntity = panels.firstOrNull { it.state != "unavailable" && it.state != "unknown" }
            ?: panels.firstOrNull()
        val alarm = alarmEntity?.let { alarmView(it.state) }

        // Plain-language security read-out (unlocked locks, open contacts, life-safety, offline).
        // Pure + unit-tested in core/logic/Security.kt. The device index dedupes each Schlage lock's
        // companion door sensor; areas give doors human names.
        val deviceByEntity = devices.deviceByEntity
        val security = securityReadout(all, overrides, areas, deviceByEntity)

        val resolvedBase = baseUrl.ifEmpty { null }
        // Collapse ring-mqtt's per-device entities into one logical camera each.
        val logical = resolveCameras(entities, overrides)
        val cameras = logical.map { lc ->
            CameraUi(
                id = lc.id,
                entityId = lc.liveEntity.entityId,
                name = lc.name,
                live = isCameraLive(lc.snapshotEntity),
                // Age badge: last_changed is useless here — a camera's state rarely transitions, so
                // it reads hours-stale even on a live feed (the "15h ago" bug). Try a "timestamp"
                // attr first IF the snapshot capture time is ever exposed on the entity (today it
                // isn't — ring-mqtt sets no json_attributes_topic), then fall back to last_updated,
                // which DOES bump on each ~30s snapshot republish for an open camera.
                lastChangedMs = lastChangedMs(lc.snapshotEntity.stringAttr("timestamp"))
                    ?: lastChangedMs(lc.snapshotEntity.lastUpdated)
                    ?: lastChangedMs(lc.snapshotEntity.lastChanged),
                // The _snapshot still IS ring-mqtt's freshest frame (republished ~every 30s for an
                // open camera); cache-busting re-fetches it. Do NOT source from the live entity —
                // camera_proxy on an idle go2rtc stream returns stale/black or errors.
                snapshotUrl = snapshotUrl(lc.snapshotEntity, resolvedBase),
                streamUrl = streamUrl(lc.liveEntity, resolvedBase),
                eventSelectId = lc.eventSelectId,
                eventStreamId = lc.eventStreamId,
                dingId = lc.dingId,
                sirenSwitchId = lc.sirenSwitchId,
            )
        }
        val doorbell = activeDoorbellPress(logical, entities, System.currentTimeMillis())

        val rooms = groupByArea(all, areas)

        return HomeUi(
            status = status,
            error = error,
            alarm = alarm,
            alarmEntityId = alarmEntity?.entityId,
            alarmRawState = alarmEntity?.state,
            securitySummary = security.summary,
            secureAllClear = security.allClear,
            offlineLabel = security.offlineLabel,
            cameras = cameras,
            liveCameraCount = cameras.count { it.live },
            doorbell = doorbell,
            roomCount = rooms.size,
            roomsPreview = rooms.take(4).joinToString(" · ") { it.area },
            lifeSafetyAlerts = security.lifeSafetyAlerts,
            lifeSafetyMonitored = security.lifeSafetyMonitored,
        )
    }
}
