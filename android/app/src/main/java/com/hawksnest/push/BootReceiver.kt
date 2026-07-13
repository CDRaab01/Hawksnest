package com.hawksnest.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts the push service after a device reboot — but only if the user has push
 * enabled, so a reboot never spins up a foreground service the user turned off.
 * (START_STICKY covers process death while running; this covers a full reboot.)
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var pushSettings: PushSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (pushSettings.enabled.first()) {
                    NtfyPushService.start(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
