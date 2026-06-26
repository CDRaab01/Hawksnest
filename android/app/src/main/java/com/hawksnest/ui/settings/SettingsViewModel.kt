package com.hawksnest.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.util.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = connectionManager.state.status
    val error: StateFlow<String?> = connectionManager.state.error

    val savedUrl: StateFlow<String?> = credentialStore.haUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasToken: StateFlow<Boolean> = credentialStore.haToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Save the URL (+ token if entered, else keep the existing one) and reconnect. */
    fun connect(url: String, token: String) {
        viewModelScope.launch {
            val tok = token.ifBlank { credentialStore.haToken.firstOrNull().orEmpty() }
            if (url.isBlank() || tok.isBlank()) return@launch
            credentialStore.save(url, tok)
            connectionManager.reconnect()
        }
    }

    /** Forget the saved credentials and fall back to demo data. */
    fun disconnect() {
        viewModelScope.launch {
            credentialStore.clear()
            connectionManager.reconnect()
        }
    }
}
