package com.hawksnest.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.devicePrefsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hawksnest_device_prefs")

private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

/**
 * On-device personalization for the Devices list: entities the user hid
 * (long-press → Hide) and user renames (long-press → Rename). Mirrors the web's
 * Customize hide/pin in spirit, persisted in a dedicated DataStore so the app
 * never needs a code deploy to tame a noisy or badly-named entity.
 */
@Singleton
class DevicePrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val hiddenKey = stringSetPreferencesKey("hidden_entities")
    private val renamesKey = stringPreferencesKey("entity_renames")

    /** Entity ids the user hid from the Devices list. */
    val hidden: Flow<Set<String>> =
        context.devicePrefsDataStore.data.map { it[hiddenKey] ?: emptySet() }

    /** entity_id → user-chosen display name. */
    val renames: Flow<Map<String, String>> =
        context.devicePrefsDataStore.data.map { prefs ->
            prefs[renamesKey]?.let {
                runCatching { Json.decodeFromString(mapSerializer, it) }.getOrNull()
            } ?: emptyMap()
        }

    suspend fun setHidden(entityId: String, hide: Boolean) {
        context.devicePrefsDataStore.edit { prefs ->
            val cur = prefs[hiddenKey] ?: emptySet()
            prefs[hiddenKey] = if (hide) cur + entityId else cur - entityId
        }
    }

    /** Set a rename; blank/null clears it (back to the automatic name chain). */
    suspend fun setRename(entityId: String, name: String?) {
        context.devicePrefsDataStore.edit { prefs ->
            val cur = prefs[renamesKey]?.let {
                runCatching { Json.decodeFromString(mapSerializer, it) }.getOrNull()
            } ?: emptyMap()
            val next = if (name.isNullOrBlank()) cur - entityId else cur + (entityId to name.trim())
            prefs[renamesKey] = Json.encodeToString(mapSerializer, next)
        }
    }
}
