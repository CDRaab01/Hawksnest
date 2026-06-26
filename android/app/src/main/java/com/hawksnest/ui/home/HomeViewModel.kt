package com.hawksnest.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.favorites
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
import com.hawksnest.core.logic.resolveName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FavoriteKind { LOCK, ALARM, OTHER }

data class CameraUi(val entityId: String, val name: String, val live: Boolean)
data class FavoriteUi(
    val entityId: String,
    val name: String,
    val stateText: String,
    val kind: FavoriteKind,
    val alarmState: String? = null,
)
data class AreaUi(val area: String, val deviceCount: Int, val preview: String)

data class HomeUi(
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val error: String? = null,
    val alarm: AlarmView? = null,
    val alarmEntityId: String? = null,
    val alarmRawState: String? = null,
    val offlineLabel: String? = null,
    val cameras: List<CameraUi> = emptyList(),
    val liveCameraCount: Int = 0,
    val favorites: List<FavoriteUi> = emptyList(),
    val areas: List<AreaUi> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val uiState: StateFlow<HomeUi> = combine(
        state.entities, state.areas, state.status, state.error,
    ) { entities, areas, status, error ->
        buildUi(entities, areas, status, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        viewModelScope.launch { connection.start() }
    }

    /** Arm/disarm, lock/unlock, etc. Non-optimistic — the store reconciles from the source echo. */
    fun callService(domain: String, service: String, entityId: String) {
        viewModelScope.launch {
            connection.callService(domain, service, ServiceData(entityId = entityId))
        }
    }

    private fun buildUi(
        entities: Map<String, HassEntity>,
        areas: Map<String, String>,
        status: ConnectionStatus,
        error: String?,
    ): HomeUi {
        val all = entities.values.toList()

        val alarmEntity = all.firstOrNull { it.entityId.startsWith("alarm_control_panel.") }
        val alarm = alarmEntity?.let { alarmView(it.state) }

        val offline = all.filter { it.state == "unavailable" }
        val offlineLabel = when {
            offline.isEmpty() -> null
            offline.size == 1 -> "${resolveName(offline[0], overrides)} is offline"
            else -> "${resolveName(offline[0], overrides)} +${offline.size - 1} more offline"
        }

        val cameras = all
            .filter { domainOf(it.entityId) == "camera" }
            .sortedBy { it.entityId }
            .map {
                val live = it.stringAttr("entity_picture") != null && it.state != "unavailable"
                CameraUi(it.entityId, resolveName(it, overrides), live)
            }

        val favs = favorites.mapNotNull { id -> entities[id] }.map { e ->
            val domain = domainOf(e.entityId)
            val kind = when (domain) {
                "lock" -> FavoriteKind.LOCK
                "alarm_control_panel" -> FavoriteKind.ALARM
                else -> FavoriteKind.OTHER
            }
            FavoriteUi(
                entityId = e.entityId,
                name = resolveName(e, overrides),
                stateText = e.state.replaceFirstChar { it.uppercaseChar() },
                kind = kind,
                alarmState = if (kind == FavoriteKind.ALARM) e.state else null,
            )
        }

        val areaGroups = groupByArea(all, areas).map { g ->
            AreaUi(
                area = g.area,
                deviceCount = g.entities.size,
                preview = g.entities.take(3).joinToString(" · ") { resolveName(it, overrides) },
            )
        }

        return HomeUi(
            status = status,
            error = error,
            alarm = alarm,
            alarmEntityId = alarmEntity?.entityId,
            alarmRawState = alarmEntity?.state,
            offlineLabel = offlineLabel,
            cameras = cameras,
            liveCameraCount = cameras.count { it.live },
            favorites = favs,
            areas = areaGroups,
        )
    }
}
