package com.hawksnest

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.push.FcmEnrollment
import com.hawksnest.push.PushChannels
import dagger.hilt.android.HiltAndroidApp
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
    @Inject lateinit var fcmEnrollment: FcmEnrollment

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
        PushChannels.createChannels(this)
        // Manual Firebase init (no google-services.json); no-op until FCM is configured.
        fcmEnrollment.init()
        fcmEnrollment.enroll()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // The app client has no read timeout (the WS is long-lived); give image loads a sane one.
            .okHttpClient(okHttpClient.newBuilder().readTimeout(15, TimeUnit.SECONDS).build())
            .crossfade(true)
            .build()
}
