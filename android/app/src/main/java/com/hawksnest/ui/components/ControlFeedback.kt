package com.hawksnest.ui.components

import androidx.lifecycle.ViewModel
import com.hawksnest.core.ha.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Exposes the control gate's failure stream to the navigation shell — the one snackbar host in
 * [com.hawksnest.ui.navigation.AppNavGraph] collects it, so every screen's control failures
 * ("Front Door didn't respond.", "Couldn't reach Alarm — not connected.") surface identically
 * without per-screen plumbing.
 */
@HiltViewModel
class ControlFeedbackViewModel @Inject constructor(
    connection: ConnectionManager,
) : ViewModel() {
    /** Human-readable control failures (see `ControlGate.errors`). */
    val errors: SharedFlow<String> = connection.controlErrors
}
