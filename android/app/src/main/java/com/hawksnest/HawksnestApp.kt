package com.hawksnest

import android.app.Application
import com.hawksnest.core.ha.ConnectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt application root. Starts the [ConnectionManager] in its app-scoped coroutine so the HA
 * WebSocket (or demo source) outlives any individual screen. (Phase 4 will also create the push
 * notification channels here.)
 */
@HiltAndroidApp
class HawksnestApp : Application() {

    @Inject lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
    }
}
