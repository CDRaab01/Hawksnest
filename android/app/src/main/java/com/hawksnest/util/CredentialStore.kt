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
    private val cipher = TokenCipher()
    private val haUrlKey = stringPreferencesKey("ha_url")
    /** Ciphertext of the token (base64 IV||GCM). */
    private val haTokenEncKey = stringPreferencesKey("ha_token_enc")
    /** Legacy plaintext token key — read-then-migrate away from it (pre-encryption installs). */
    private val legacyTokenKey = stringPreferencesKey("ha_token")

    val haUrl: Flow<String?> = context.credentialDataStore.data.map { it[haUrlKey] }

    /** The decrypted token, or the legacy plaintext one until [migrateLegacyToken] moves it. */
    val haToken: Flow<String?> = context.credentialDataStore.data.map { prefs ->
        val enc = prefs[haTokenEncKey]
        if (enc != null) cipher.decrypt(enc) else prefs[legacyTokenKey]
    }

    suspend fun save(url: String, token: String) {
        val enc = cipher.encrypt(token.trim())
        context.credentialDataStore.edit {
            it[haUrlKey] = url.trim()
            if (enc != null) it[haTokenEncKey] = enc else it.remove(haTokenEncKey)
            it.remove(legacyTokenKey) // never keep a plaintext copy alongside
        }
    }

    suspend fun clear() {
        context.credentialDataStore.edit {
            it.remove(haUrlKey)
            it.remove(haTokenEncKey)
            it.remove(legacyTokenKey)
        }
    }

    /**
     * One-time upgrade of a pre-encryption install: if a plaintext token is present, re-store it
     * encrypted and delete the plaintext. Idempotent and cheap — safe to call on every app start.
     */
    suspend fun migrateLegacyToken() {
        context.credentialDataStore.edit { prefs ->
            val legacy = prefs[legacyTokenKey]
            if (legacy != null) {
                cipher.encrypt(legacy)?.let { prefs[haTokenEncKey] = it }
                prefs.remove(legacyTokenKey)
            }
        }
    }
}
