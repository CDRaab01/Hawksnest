package com.hawksnest.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.util.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Result of the Tailscale reachability probe (a one-shot HTTP check against the saved base URL). */
enum class Reachability { Idle, Checking, Reachable, Unreachable }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val connectionManager: ConnectionManager,
    okHttpClient: OkHttpClient,
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = connectionManager.state.status
    val error: StateFlow<String?> = connectionManager.state.error

    val savedUrl: StateFlow<String?> = credentialStore.haUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasToken: StateFlow<Boolean> = credentialStore.haToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _reachability = MutableStateFlow(Reachability.Idle)
    /** Live result of the last [testReachability] run (Idle until the user taps Test). */
    val reachability: StateFlow<Reachability> = _reachability.asStateFlow()

    // The shared client has no read timeout (the WS is long-lived); a probe must not hang forever,
    // so it gets its own bounded-timeout copy (cheap — connection pool/dispatcher are shared).
    private val probeClient: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Probe whether the base URL's host answers over the current network (i.e. the Tailscale tunnel
     * is up and routing to the proxy). Any HTTP response — even 401/404 — means the host is
     * reachable; only a transport failure (no route, refused, timeout) is [Reachability.Unreachable].
     */
    fun testReachability(url: String) {
        val target = url.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            _reachability.value = Reachability.Checking
            val reachable = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(target.trimEnd('/') + "/").get().build()
                    probeClient.newCall(req).execute().use { true }
                } catch (e: IllegalArgumentException) {
                    false // malformed URL
                } catch (e: Exception) {
                    false // UnknownHost / connect refused / timeout → not reachable
                }
            }
            _reachability.value = if (reachable) Reachability.Reachable else Reachability.Unreachable
        }
    }

    /** Reset the probe result (e.g. when the user edits the URL). */
    fun resetReachability() {
        _reachability.value = Reachability.Idle
    }

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
