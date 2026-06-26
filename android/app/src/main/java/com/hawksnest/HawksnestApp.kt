package com.hawksnest

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt application root. Phase 1 will start the [ConnectionManager] here (connect to HA on
 * foreground); Phase 4 will create the push notification channels.
 */
@HiltAndroidApp
class HawksnestApp : Application()
