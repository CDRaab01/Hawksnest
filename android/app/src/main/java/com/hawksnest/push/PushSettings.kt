package com.hawksnest.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// A dedicated DataStore file — NOT "hawksnest_prefs" (that name is owned by
// CredentialStore; two preferencesDataStore delegates with the same name in one
// process throw at first access).
private val Context.pushDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hawksnest_push")

/**
 * Persists the push subscription preference: whether it's on, and the ntfy
 * endpoint (base URL + topic) the foreground service subscribes to. Off by
 * default — the user opts in from Settings (which also requests POST_NOTIFICATIONS).
 * Mirrors [com.hawksnest.util.CredentialStore]'s manual-write DataStore style.
 */
@Singleton
class PushSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("push_enabled")
    private val baseUrlKey = stringPreferencesKey("ntfy_base_url")
    private val topicKey = stringPreferencesKey("ntfy_topic")

    val enabled: Flow<Boolean> = context.pushDataStore.data.map { it[enabledKey] ?: false }
    val baseUrl: Flow<String> = context.pushDataStore.data.map { it[baseUrlKey] ?: DEFAULT_BASE_URL }
    val topic: Flow<String> = context.pushDataStore.data.map { it[topicKey] ?: DEFAULT_TOPIC }

    suspend fun setEnabled(on: Boolean) {
        context.pushDataStore.edit { it[enabledKey] = on }
    }

    suspend fun setEndpoint(baseUrl: String, topic: String) {
        context.pushDataStore.edit {
            it[baseUrlKey] = baseUrl.trim().trimEnd('/')
            it[topicKey] = topic.trim()
        }
    }

    companion object {
        // Defaults match the hawksnest-automation ntfy deployment (Tailscale Serve
        // :8444 front, topic set by the HA automations). See that repo's
        // docs/ntfy-push.md.
        const val DEFAULT_BASE_URL = "https://dragonfly.tail2ce561.ts.net:8444"
        const val DEFAULT_TOPIC = "hawksnest-alerts"
    }
}
