package com.hawksnest

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.crash.CrashReporter
import com.hawksnest.crash.CrashUploader
import com.hawksnest.shortcuts.ShortcutPublisher
import com.hawksnest.push.NtfyPushService
import com.hawksnest.push.PushNotifier
import com.hawksnest.push.PushSettings
import com.hawksnest.widget.WidgetLiveBridge
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
 * network policy). Also creates the push notification channels.
 */
@HiltAndroidApp
class HawksnestApp : Application(), ImageLoaderFactory {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var pushNotifier: PushNotifier
    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var widgetLiveBridge: WidgetLiveBridge
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var crashUploader: CrashUploader
    @Inject lateinit var shortcutPublisher: ShortcutPublisher

    override fun onCreate() {
        super.onCreate()
        // FIRST, before anything else can throw: a crash during startup is exactly the one we
        // most want captured, and installing this later would miss it.
        crashReporter.install()
        connectionManager.start()
        // Mirror entity changes into any home-screen widgets while this process is alive. Purely
        // additive — widgets read for themselves when the app isn't running.
        widgetLiveBridge.start()
        // Notification channels must exist before the first notify(); create them
        // once here. Then resume the push listener if the user has it enabled
        // (the service self-stops if not).
        pushNotifier.createChannels()
        // Launcher shortcuts are rebuilt from the entities that actually exist, so they appear
        // once connected and adapt if the house changes.
        shortcutPublisher.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (pushSettings.enabled.first()) {
                NtfyPushService.start(this@HawksnestApp)
            }
            // Anything captured on a previous run goes out now — never from the dying
            // process itself (see CrashReporter).
            crashUploader.sendPending()
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // The app client has no read timeout (the WS is long-lived); give image loads a sane one.
            .okHttpClient(okHttpClient.newBuilder().readTimeout(15, TimeUnit.SECONDS).build())
            .crossfade(true)
            .build()
}
