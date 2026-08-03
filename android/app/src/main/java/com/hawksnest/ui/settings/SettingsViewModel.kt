package com.hawksnest.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.net.ReachabilityProbe
import com.hawksnest.crash.CrashReporter
import com.hawksnest.core.logic.ThemePref
import com.hawksnest.crash.CrashStore
import com.hawksnest.util.DevicePrefsStore
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
    private val crashStore: CrashStore,
    private val devicePrefs: DevicePrefsStore,
    @ApplicationContext private val appContext: Context,
    okHttpClient: OkHttpClient,
) : ViewModel() {

    /**
     * Recent crashes, read from disk rather than observed: files only change when the process
     * dies, so there is nothing to keep a Flow open for. Re-read on clear.
     */
    private val _crashes = MutableStateFlow(readCrashes())
    val crashes: StateFlow<List<CrashEntry>> = _crashes.asStateFlow()

    private fun readCrashes(): List<CrashEntry> = crashStore.list().map { f ->
        val body = crashStore.read(f)
        val whenMs = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull()
        // The stored body already leads with "type:" / "message:" lines; surface the type plus
        // when it happened, which is what distinguishes one entry from another in a list.
        val type = body.lineSequence().firstOrNull { it.startsWith("type:") }
            ?.removePrefix("type:")?.trim()?.substringAfterLast('.') ?: "Crash"
        val stamp = whenMs?.let { CrashReporter.isoUtc(it) } ?: f.name
        CrashEntry(id = f.name, headline = "$type · $stamp", body = body)
    }

    fun clearCrashes() {
        crashStore.clear()
        _crashes.value = emptyList()
    }

    /** Appearance preference (Dark / Light / System) — applied by MainActivity. */
    val themePref: StateFlow<ThemePref> = devicePrefs.themePref
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePref.DEFAULT)

    fun setThemePref(pref: ThemePref) {
        viewModelScope.launch { devicePrefs.setThemePref(pref) }
    }

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

    /**
     * Household policy for camera object alerts, held in HA as `input_boolean`s.
     *
     * Deliberately NOT device-local like [pushEnabled]: these stop the push being
     * *generated*, so they apply to every device and never wake a radio for an
     * alert nobody wants ("servers enforce, clients present"). [pushEnabled] is
     * this phone's subscription; these are the household's policy.
     *
     * Null = the helper doesn't exist on this HA (or we're not connected yet), and
     * the UI hides the switch rather than showing a control that silently does
     * nothing. Mirrors the automation's own gate.
     */
    val personAlerts: StateFlow<Boolean?> = booleanHelper(HELPER_PERSON)
    val petAlerts: StateFlow<Boolean?> = booleanHelper(HELPER_PETS)

    private fun booleanHelper(entityId: String): StateFlow<Boolean?> =
        connectionManager.state.entities
            .map { it[entityId]?.state?.let { s -> s == "on" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Flip a helper. Crash-safe via the control gate, like every other toggle. */
    fun setPersonAlerts(on: Boolean) = setHelper(HELPER_PERSON, on, "Person alerts")

    fun setPetAlerts(on: Boolean) = setHelper(HELPER_PETS, on, "Pet alerts")

    private fun setHelper(entityId: String, on: Boolean, label: String) {
        connectionManager.control(entityId, if (on) "turn_on" else "turn_off", label = label)
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

    // ---- Direct camera RTSP (the top live tier) ----------------------------------------------

    val rtspUser: StateFlow<String?> = credentialStore.rtspUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasRtspPass: StateFlow<Boolean> = credentialStore.rtspPass
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val rtspCameraIps: StateFlow<Map<String, String>> = credentialStore.rtspCameraIps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Save the camera RTSP account and camera→IP map. A blank password keeps the stored one, so the
     * user can edit the IP list without re-typing it (same behaviour as the HA token field above).
     * No reconnect: the player reads this per camera open, and it is not part of the HA session.
     */
    fun saveRtsp(user: String, pass: String, cameraIps: Map<String, String>) {
        viewModelScope.launch {
            val existing = credentialStore.rtspPass.firstOrNull().orEmpty()
            credentialStore.saveRtsp(user, pass.ifBlank { existing }, cameraIps)
        }
    }

    /** Turn the tier off and forget the camera credentials, leaving the HA session untouched. */
    fun clearRtsp() {
        viewModelScope.launch { credentialStore.saveRtsp("", "", emptyMap()) }
    }

    companion object {
        /** Defined in the HA seed (`hawksnest-automation`), read by
         *  `hawksnest_push_camera_object`. */
        const val HELPER_PERSON = "input_boolean.hawksnest_alert_person"
        const val HELPER_PETS = "input_boolean.hawksnest_alert_pets"
    }
}
