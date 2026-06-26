package com.hawksnest.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.credentialDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hawksnest_prefs")

/**
 * Persists the Home Assistant connection credentials — the base URL and a long-lived access token.
 * Mirrors Spotter's `util/TokenStore` DataStore pattern. The LLAT is a full HA credential;
 * DataStore is app-private (a Keystore-wrap is a sensible follow-up).
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val haUrlKey = stringPreferencesKey("ha_url")
    private val haTokenKey = stringPreferencesKey("ha_token")

    val haUrl: Flow<String?> = context.credentialDataStore.data.map { it[haUrlKey] }
    val haToken: Flow<String?> = context.credentialDataStore.data.map { it[haTokenKey] }

    suspend fun save(url: String, token: String) {
        context.credentialDataStore.edit {
            it[haUrlKey] = url.trim()
            it[haTokenKey] = token.trim()
        }
    }

    suspend fun clear() {
        context.credentialDataStore.edit {
            it.remove(haUrlKey)
            it.remove(haTokenKey)
        }
    }
}
