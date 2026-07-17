package com.hawksnest

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.push.NtfyPushService
import com.hawksnest.push.PushNotifier
import com.hawksnest.push.PushSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Hilt application root. Starts the [ConnectionManager] in its app-scoped coroutine so the HA
 * WebSocket (or demo source) outlives any individual screen, and supplies Coil an [ImageLoader]
 * backed by the app OkHttp client (so camera snapshots reuse the same connection pool + cleartext
 * network policy). (Phase 4 will also create the push notification channels here.)
 */
@HiltAndroidApp
class HawksnestApp : Application(), ImageLoaderFactory {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var pushNotifier: PushNotifier
    @Inject lateinit var pushSettings: PushSettings

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
        // Notification channels must exist before the first notify(); create them
        // once here. Then resume the push listener if the user has it enabled
        // (the service self-stops if not).
        pushNotifier.createChannels()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (pushSettings.enabled.first()) {
                NtfyPushService.start(this@HawksnestApp)
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // The app client has no read timeout (the WS is long-lived); give image loads a sane one.
            .okHttpClient(okHttpClient.newBuilder().readTimeout(15, TimeUnit.SECONDS).build())
            .crossfade(true)
            .build()
}
