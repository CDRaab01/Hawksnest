package com.hawksnest.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.net.ReachabilityProbe
import com.hawksnest.push.NtfyPushService
import com.hawksnest.push.PushSettings
import com.hawksnest.util.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Result of the Tailscale reachability probe (a one-shot HTTP check against the saved base URL). */
enum class Reachability { Idle, Checking, Reachable, Unreachable }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val connectionManager: ConnectionManager,
    private val pushSettings: PushSettings,
    @ApplicationContext private val appContext: Context,
    okHttpClient: OkHttpClient,
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = connectionManager.state.status
    val error: StateFlow<String?> = connectionManager.state.error

    /** Whether push notifications are enabled (the foreground ntfy listener). */
    val pushEnabled: StateFlow<Boolean> = pushSettings.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Persist the push preference and start/stop the listener. The caller (the
     * Settings UI) is responsible for having obtained POST_NOTIFICATIONS first
     * when turning on — without it the service runs but its notifications no-op.
     */
    fun setPushEnabled(on: Boolean) {
        viewModelScope.launch {
            pushSettings.setEnabled(on)
            if (on) NtfyPushService.start(appContext) else NtfyPushService.stop(appContext)
        }
    }

    val savedUrl: StateFlow<String?> = credentialStore.haUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasToken: StateFlow<Boolean> = credentialStore.haToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _reachability = MutableStateFlow(Reachability.Idle)
    /** Live result of the last [testReachability] run (Idle until the user taps Test). */
    val reachability: StateFlow<Reachability> = _reachability.asStateFlow()

    // The bounded-timeout probe shared with the reconnect loop's offline hint (extracted to
    // core/net/ReachabilityProbe so both surfaces classify reachability identically).
    private val probe = ReachabilityProbe.from(okHttpClient)

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
            _reachability.value =
                if (probe.isReachable(target)) Reachability.Reachable else Reachability.Unreachable
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
