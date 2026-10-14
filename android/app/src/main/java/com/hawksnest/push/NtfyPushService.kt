package com.hawksnest.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Foreground service that keeps a single streaming connection to the self-hosted
 * ntfy server (`<base>/<topic>/json`) and raises a notification for each message.
 * This is what makes push work with the app closed — no FCM, purely the tailnet.
 *
 * It runs as a `specialUse` foreground service (the honest type for "hold a
 * connection to a self-hosted server"); reconnects with a capped backoff that only
 * resets once a stream has proven healthy ([NtfyBackoff]); makes no attempt at all
 * while there is no network, and retries at once when one appears; and
 * self-stops if push was turned off. START_STICKY so Android restarts it after a
 * kill, and [BootReceiver] restarts it after a reboot.
 *
 * On-device verification is still pending (see docs/CAMERA-SMOKE.md "push fires"):
 * the streaming/backoff/battery lifecycle can only be proven on the real phone.
 */
@AndroidEntryPoint
class NtfyPushService : Service() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var notifier: PushNotifier
    @Inject lateinit var json: Json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null

    // Starts false and is set by the callback, which the platform fires immediately on
    // registration when a network already exists — so "unknown" and "offline" are the same state
    // and neither makes a request.
    private val networkUp = MutableStateFlow(false)
    private val networkGeneration = MutableStateFlow(0)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var activeCall: Call? = null

    // A bounded read timeout (> ntfy's ~45s keepalive) so a silently-dropped
    // network surfaces as an error and triggers a reconnect instead of hanging.
    //
    // No OkHttp ping: the app client's 20s `pingInterval` exists for the HA WebSocket, but it also
    // applies to HTTP/2 connections — which is what this stream is behind the Tailscale Serve TLS
    // front. Inherited, it woke the radio every 20s around the clock on the one connection that
    // is *meant* to sit idle in a pocket, to detect a drop the read timeout above already detects.
    private val streamClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(75, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        watchNetwork()
        if (streamJob?.isActive != true) {
            streamJob = scope.launch { runStream() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, SERVICE_ID, notifier.serviceNotification(), type)
    }

    private suspend fun runStream() {
        if (!pushSettings.enabled.first()) {
            stopSelfSafely()
            return
        }
        val base = pushSettings.baseUrl.first().trimEnd('/')
        val topic = pushSettings.topic.first()
        val url = "$base/$topic/json"
        val stream = NtfyStream(streamClient)
        val backoff = NtfyBackoff()

        while (scope.isActive) {
            // No network, no attempt — and no polling for one either. This suspends until
            // `onAvailable` flips the flag, which costs nothing; the old loop woke the radio
            // every 60s all night in a dead zone to learn what the platform will tell us for free.
            networkUp.first { it }
            val generation = networkGeneration.value

            val session = try {
                stream.listen(
                    url = url,
                    onCall = { activeCall = it },
                    keepGoing = { scope.isActive },
                ) { line -> NtfyMessage.parse(line, json)?.let { notifier.show(it) } }
            } catch (e: NtfyStreamException) {
                if (!scope.isActive) return
                Log.w(TAG, "ntfy stream dropped after ${e.session.linesRead} lines", e)
                e.session
            } catch (e: Exception) {
                // Anything that isn't I/O (a notification that failed to post, say). It must not
                // kill the listener, and it proves nothing about the stream — so it backs off.
                if (e is CancellationException) throw e
                Log.w(TAG, "ntfy listener failed outside the stream", e)
                NtfySession(linesRead = 0, durationMs = 0)
            } finally {
                activeCall = null
            }
            if (!scope.isActive) return

            // A normal return is NOT treated as success here — that was the bug. Whether the wait
            // resets is decided by what the session did, never by how it ended (see NtfyBackoff).
            val waitMs = backoff.next(session)
            Log.i(TAG, "ntfy reconnect in ${waitMs}ms")
            // Wait out the backoff, unless the network changes first. Comparing against the
            // generation captured BEFORE the attempt means a network that arrived while the
            // attempt was still failing is noticed too, rather than slept through.
            val networkChanged = withTimeoutOrNull(waitMs) {
                networkGeneration.first { it != generation }
            }
            if (networkChanged != null) backoff.reset()
        }
    }

    /**
     * Follow the default network for as long as the service lives.
     *
     * Two signals come out of it: [networkUp] gates attempts, and [networkGeneration] ticks on
     * every `onAvailable` — including a Wi-Fi-to-cellular handover, where the network never went
     * away but every socket on the old one is dead. Both wake the retry wait.
     *
     * If registration itself fails (the platform caps callbacks per app), the listener must still
     * work, so it falls back to assuming a network and relying on the backoff alone.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkUp.value = true
                networkGeneration.update { it + 1 }
            }

            // For a default-network callback this fires only when there is no default network
            // left at all, not on a handover — so it really does mean "offline".
            override fun onLost(network: Network) {
                networkUp.value = false
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not watch the network; falling back to plain backoff", e)
            networkUp.value = true
        }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Network callback was already gone", e)
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unwatchNetwork()
        scope.cancel()
        // Cancelling the scope does not interrupt a blocking socket read; without this the stream
        // would sit open until its 75s read timeout after the service was told to stop.
        activeCall?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "NtfyPushService"
        private const val SERVICE_ID = 4201

        /**
         * Bring the listener up, if Android will currently allow it.
         *
         * **This must never throw, and that is the whole point of the try.** From Android 12 a
         * foreground service may not be started while the app is in the background, and the
         * refusal arrives as `ForegroundServiceStartNotAllowedException` — a subclass of
         * `IllegalStateException`, which is why it can be caught here without an API guard.
         *
         * It used to be allowed to propagate, and because [com.hawksnest.HawksnestApp.onCreate]
         * calls this from a bare `CoroutineScope`, propagating meant **killing the process**.
         * `Application.onCreate` runs on *every* process creation, and a widget tap creates a
         * process — so a tap on a home-screen widget with the app closed crashed the app before
         * the service call it was supposed to send ever left the device. The widget looked dead
         * and the light never changed. That is the bug this catch exists for; push failing to
         * start is a nuisance, and losing every widget tap is not.
         *
         * When it is refused, push simply stays down until something starts it from a context
         * Android accepts — which is why `MainActivity` retries on open. Returns whether the
         * service was asked to start, for callers that want to know.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyPushService::class.java),
            )
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Not allowed to start the push listener from the background right now", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NtfyPushService::class.java))
        }
    }
}
