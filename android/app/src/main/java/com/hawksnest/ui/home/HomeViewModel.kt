package com.hawksnest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.AlarmView
import com.hawksnest.core.logic.alarmView
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.isCameraLive
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.snapshotUrl
import com.hawksnest.core.logic.streamUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CameraUi(
    val entityId: String,
    val name: String,
    val live: Boolean,
    /** Signed snapshot URL resolved against the HA origin (null in demo / when down). */
    val snapshotUrl: String? = null,
    /** Derived MJPEG live-stream URL (used by the camera lightbox). */
    val streamUrl: String? = null,
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
    val roomCount: Int = 0,
    val roomsPreview: String = "",
    /** Triggered life-safety sensors (smoke/CO/gas/leak), surfaced regardless of armed state. */
    val lifeSafetyAlerts: List<String> = emptyList(),
    val lifeSafetyMonitored: Int = 0,
    /** The alarm panel enforces a disarm code (HA `code_format`) → show the PIN keypad. */
    val alarmRequiresCode: Boolean = false,
)

private val DOOR_CLASSES = setOf("door", "window", "garage_door")
private val LIFE_SAFETY_CLASSES = setOf("smoke", "carbon_monoxide", "gas", "moisture")

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val uiState: StateFlow<HomeUi> = combine(
        state.entities, state.areas, state.status, state.error, state.baseUrl,
    ) { entities, areas, status, error, baseUrl ->
        buildUi(entities, areas, status, error, baseUrl)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        viewModelScope.launch { connection.start() }
    }

    /** Arm/disarm. Non-optimistic — the store reconciles from the source echo. A [code] (from the
     *  PIN keypad) is forwarded to HA as service data when the panel requires one. */
    fun arm(service: String, code: String? = null) {
        val id = uiState.value.alarmEntityId ?: return
        val extra = if (code.isNullOrEmpty()) emptyMap() else mapOf("code" to code)
        viewModelScope.launch {
            connection.callService("alarm_control_panel", service, ServiceData(entityId = id, extra = extra))
        }
    }

    private fun buildUi(
        entities: Map<String, HassEntity>,
        areas: Map<String, String>,
        status: ConnectionStatus,
        error: String?,
        baseUrl: String,
    ): HomeUi {
        val all = entities.values.toList()

        val alarmEntity = all.firstOrNull { it.entityId.startsWith("alarm_control_panel.") }
        val alarm = alarmEntity?.let { alarmView(it.state) }

        // Plain-language security summary (unlocked locks + open door/window contacts).
        val unlocked = all.filter {
            domainOf(it.entityId) == "lock" && it.state != "locked" && it.state != "locking"
        }
        val openDoors = all.filter {
            domainOf(it.entityId) == "binary_sensor" &&
                it.stringAttr("device_class") in DOOR_CLASSES && it.state == "on"
        }
        val parts = unlocked.map { "${resolveName(it, overrides)} unlocked" } +
            openDoors.map { "${resolveName(it, overrides)} open" }
        val secureAllClear = parts.isEmpty()
        val securitySummary = if (secureAllClear) "All doors locked" else parts.joinToString(" · ")

        val offline = all.filter { it.state == "unavailable" }
        val offlineLabel = when {
            offline.isEmpty() -> null
            offline.size == 1 -> "${resolveName(offline[0], overrides)} is offline"
            else -> "${resolveName(offline[0], overrides)} +${offline.size - 1} more offline"
        }

        val resolvedBase = baseUrl.ifEmpty { null }
        val cameras = all
            .filter { domainOf(it.entityId) == "camera" }
            .sortedBy { it.entityId }
            .map {
                CameraUi(
                    entityId = it.entityId,
                    name = resolveName(it, overrides),
                    live = isCameraLive(it),
                    snapshotUrl = snapshotUrl(it, resolvedBase),
                    streamUrl = streamUrl(it, resolvedBase),
                )
            }

        // Life-safety is an always-on channel — surfaced no matter the armed state.
        val lifeSafety = all.filter {
            domainOf(it.entityId) == "binary_sensor" && it.stringAttr("device_class") in LIFE_SAFETY_CLASSES
        }
        val lifeSafetyAlerts = lifeSafety.filter { it.state == "on" }.map { resolveName(it, overrides) }

        val rooms = groupByArea(all, areas)

        return HomeUi(
            status = status,
            error = error,
            alarm = alarm,
            alarmEntityId = alarmEntity?.entityId,
            alarmRawState = alarmEntity?.state,
            securitySummary = securitySummary,
            secureAllClear = secureAllClear,
            offlineLabel = offlineLabel,
            cameras = cameras,
            liveCameraCount = cameras.count { it.live },
            roomCount = rooms.size,
            roomsPreview = rooms.take(4).joinToString(" · ") { it.area },
            lifeSafetyAlerts = lifeSafetyAlerts,
            lifeSafetyMonitored = lifeSafety.size,
            alarmRequiresCode = alarmEntity?.stringAttr("code_format") != null,
        )
    }
}
