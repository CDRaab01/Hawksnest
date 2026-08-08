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
import com.hawksnest.core.logic.ThemePref
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.devicePrefsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hawksnest_device_prefs")

private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
private val listSerializer = ListSerializer(String.serializer())

/**
 * On-device personalization for the Devices list: entities the user hid
 * (long-press → Hide), user renames (long-press → Rename), and the ordered
 * pinned rail (long-press → Pin). Mirrors the web's Customize pin/hide,
 * persisted in a dedicated DataStore so the app never needs a code deploy to
 * tame a noisy or badly-named entity. This store is deliberately backed up
 * (see BackupExclusionTest) — right for preferences, unlike the token store.
 */
@Singleton
class DevicePrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val hiddenKey = stringSetPreferencesKey("hidden_entities")
    private val renamesKey = stringPreferencesKey("entity_renames")
    private val themeKey = stringPreferencesKey("theme_pref")

    // A JSON string array, not a stringSetPreferencesKey — pin order is the display
    // order (same reason `entity_renames` above is JSON-in-a-string).
    private val pinnedKey = stringPreferencesKey("pinned_entities")

    /** Entity ids the user hid from the Devices list. */
    val hidden: Flow<Set<String>> =
        context.devicePrefsDataStore.data.map { it[hiddenKey] ?: emptySet() }

    /**
     * Appearance preference (Dark / Light / System). Lives here rather than in CredentialStore
     * because it is device personalization, not a credential — and this DataStore is the one
     * that is deliberately backed up (see BackupExclusionTest), which is right for a preference.
     */
    val themePref: Flow<ThemePref> =
        context.devicePrefsDataStore.data.map { ThemePref.parse(it[themeKey]) }

    suspend fun setThemePref(pref: ThemePref) {
        context.devicePrefsDataStore.edit { it[themeKey] = pref.name }
    }

    /** entity_id → user-chosen display name. */
    val renames: Flow<Map<String, String>> =
        context.devicePrefsDataStore.data.map { prefs ->
            prefs[renamesKey]?.let {
                runCatching { Json.decodeFromString(mapSerializer, it) }.getOrNull()
            } ?: emptyMap()
        }

    /**
     * The user's pinned entity ids, in display order. `null` = the key was never
     * written — "never customized", callers fall back to the `config/Favorites`
     * seed via `core/logic effectivePins` (exact web `prefsStore.ts` semantics:
     * the first edit materializes the seed).
     */
    val pinned: Flow<List<String>?> =
        context.devicePrefsDataStore.data.map { prefs ->
            prefs[pinnedKey]?.let {
                runCatching { Json.decodeFromString(listSerializer, it) }.getOrNull()
            }
        }

    /** Persist the full pinned list (callers compute it with the pure pin helpers). */
    suspend fun setPinned(entityIds: List<String>) {
        context.devicePrefsDataStore.edit { prefs ->
            prefs[pinnedKey] = Json.encodeToString(listSerializer, entityIds)
        }
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
