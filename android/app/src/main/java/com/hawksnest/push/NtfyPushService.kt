package com.hawksnest.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Foreground service that keeps a single streaming connection to the self-hosted
 * ntfy server (`<base>/<topic>/json`) and raises a notification for each message.
 * This is what makes push work with the app closed — no FCM, purely the tailnet.
 *
 * It runs as a `specialUse` foreground service (the honest type for "hold a
 * connection to a self-hosted server"); reconnects with capped backoff; and
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

    // A bounded read timeout (> ntfy's ~45s keepalive) so a silently-dropped
    // network surfaces as an error and triggers a reconnect instead of hanging.
    private val streamClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
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
        var backoffMs = MIN_BACKOFF_MS

        while (scope.isActive) {
            try {
                connectAndListen(url)
                // Returned normally = server closed the stream; reconnect promptly.
                backoffMs = MIN_BACKOFF_MS
            } catch (e: Exception) {
                if (!scope.isActive) return
                Log.w(TAG, "ntfy stream dropped, retrying in ${backoffMs}ms", e)
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndListen(url: String) {
        val request = Request.Builder().url(url).get().build()
        streamClient.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: return
            while (scope.isActive) {
                scope.coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: return // null = stream closed
                NtfyMessage.parse(line, json)?.let { notifier.show(it) }
            }
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "NtfyPushService"
        private const val SERVICE_ID = 4201
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyPushService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NtfyPushService::class.java))
        }
    }
}
