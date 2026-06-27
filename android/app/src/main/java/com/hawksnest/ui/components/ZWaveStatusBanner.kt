package com.hawksnest.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.logic.zwaveControllerOffline
import com.hawksnest.ui.theme.HawksnestTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Derives the app-wide Z-Wave controller-offline flag from the live state: only
 * true when connected and every Z-Wave entity is unavailable at once. Mirrors the
 * web `useZWaveControllerOffline` selector.
 */
@HiltViewModel
class ZWaveStatusViewModel @Inject constructor(connection: ConnectionManager) : ViewModel() {
    val offline: StateFlow<Boolean> =
        combine(
            connection.state.status,
            connection.state.entities,
            connection.state.zwaveEntityIds,
        ) { status, entities, ids ->
            if (status != ConnectionStatus.CONNECTED) {
                false
            } else {
                zwaveControllerOffline(ids.mapNotNull { entities[it]?.state })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * App-wide warning when the Z-Wave controller looks offline — every Z-Wave entity
 * is unavailable at once, meaning the USB stick or zwave-js-ui has dropped (the
 * deployment's "fragile link"). Critical because the locks go silent: HA shows
 * their last-known state, so without this the UI looks fine while lock/unlock no
 * longer reaches the deadbolts. Dismissible; re-shows if the radio recovers and
 * drops again. Mirrors the web `ZWaveStatusBanner`.
 */
@Composable
fun ZWaveStatusBanner(
    modifier: Modifier = Modifier,
    viewModel: ZWaveStatusViewModel = hiltViewModel(),
) {
    val offline by viewModel.offline.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(offline) { if (!offline) dismissed = false }
    if (!offline || dismissed) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = HawksnestTheme.pulse.streak)
            Column(Modifier.weight(1f)) {
                Text(
                    "Z-Wave offline",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "The controller isn't responding — your locks may not lock or unlock. " +
                        "Check the Z-Wave stick / zwave-js-ui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { dismissed = true }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
