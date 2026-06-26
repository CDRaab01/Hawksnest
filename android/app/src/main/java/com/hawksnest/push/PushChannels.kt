package com.hawksnest.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Per-urgency notification channels and the pure severity → channel routing. The HA-side sender
 * stamps a `severity` on the FCM data payload; life-safety is its own always-alerting channel
 * (smoke/CO/leak) that must surface regardless of arm state or quiet hours.
 */
object PushChannels {
    const val LIFE_SAFETY = "life_safety"
    const val SECURITY = "security"
    const val INFO = "info"

    private val LIFE_SAFETY_WORDS = setOf("life_safety", "smoke", "co", "carbon_monoxide", "gas", "leak", "water", "moisture")
    private val SECURITY_WORDS = setOf("intrusion", "alarm", "security", "triggered", "motion", "door", "window")

    /** Map a payload `severity` string to a channel id. Pure — unit-tested. */
    fun channelFor(severity: String?): String = when (severity?.lowercase()?.trim()) {
        in LIFE_SAFETY_WORDS -> LIFE_SAFETY
        in SECURITY_WORDS -> SECURITY
        else -> INFO
    }

    /** Idempotent — safe to call on every app start. */
    fun createChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(LIFE_SAFETY, "Life safety", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Smoke, CO, and leak alarms. Always alerts."
                setBypassDnd(true)
                enableVibration(true)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(SECURITY, "Security", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Intrusion and alarm events."
                enableVibration(true)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(INFO, "Activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "General home activity."
            },
        )
    }
}
