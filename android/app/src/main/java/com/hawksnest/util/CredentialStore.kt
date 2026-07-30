package com.hawksnest.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hawksnest.core.logic.decodeCameraIps
import com.hawksnest.core.logic.encodeCameraIps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.credentialDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "hawksnest_prefs")

/**
 * Persists the Home Assistant connection credentials — the base URL and a long-lived access token —
 * and the optional camera RTSP credentials for the direct-to-camera live tier.
 * Mirrors Spotter's `util/TokenStore` DataStore pattern. The LLAT is a full HA credential;
 * DataStore is app-private (a Keystore-wrap is a sensible follow-up).
 *
 * Everything here rides one DataStore file, and `res/xml/backup_rules.xml` /
 * `data_extraction_rules.xml` exclude that **whole file** from cloud backup and device transfer —
 * so keys added here inherit the exclusion automatically. Check those rules still target the file
 * (not individual keys) before adding another secret.
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

    /** Camera RTSP account, shared across the fleet (a read-only viewer account, not an admin). */
    private val rtspUserKey = stringPreferencesKey("rtsp_user")
    /** Ciphertext of the camera password — same Keystore wrap as the HA token. */
    private val rtspPassEncKey = stringPreferencesKey("rtsp_pass_enc")
    /** JSON camera-name→IP map. Not secret, but pointless to split across stores. */
    private val rtspCameraIpsKey = stringPreferencesKey("rtsp_camera_ips")

    val haUrl: Flow<String?> = context.credentialDataStore.data.map { it[haUrlKey] }

    /** The decrypted token, or the legacy plaintext one until [migrateLegacyToken] moves it. */
    val haToken: Flow<String?> = context.credentialDataStore.data.map { prefs ->
        val enc = prefs[haTokenEncKey]
        if (enc != null) cipher.decrypt(enc) else prefs[legacyTokenKey]
    }

    val rtspUser: Flow<String?> = context.credentialDataStore.data.map { it[rtspUserKey] }

    val rtspPass: Flow<String?> = context.credentialDataStore.data.map { prefs ->
        prefs[rtspPassEncKey]?.let { cipher.decrypt(it) }
    }

    /** Camera name → IP. Empty until configured, which leaves the RTSP tier off entirely. */
    val rtspCameraIps: Flow<Map<String, String>> =
        context.credentialDataStore.data.map { decodeCameraIps(it[rtspCameraIpsKey]) }

    /**
     * Store the camera RTSP config. A blank user or password clears the credential pair, which is
     * how the tier is turned back off — the player treats "no complete credentials" as "no tier".
     */
    suspend fun saveRtsp(user: String, pass: String, cameraIps: Map<String, String>) {
        val trimmedUser = user.trim()
        val enc = pass.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it.trim()) }
        context.credentialDataStore.edit {
            if (trimmedUser.isNotEmpty()) it[rtspUserKey] = trimmedUser else it.remove(rtspUserKey)
            if (enc != null) it[rtspPassEncKey] = enc else it.remove(rtspPassEncKey)
            it[rtspCameraIpsKey] = encodeCameraIps(cameraIps)
        }
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
            // Signing out drops the camera credentials too — they are a second set of house
            // credentials, and leaving them behind after a deliberate sign-out would be a surprise.
            it.remove(rtspUserKey)
            it.remove(rtspPassEncKey)
            it.remove(rtspCameraIpsKey)
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
