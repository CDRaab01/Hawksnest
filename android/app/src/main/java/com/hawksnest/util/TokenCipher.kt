package com.hawksnest.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the Home Assistant long-lived token at rest with an **AES-256-GCM key held in the
 * Android Keystore** — the key is hardware-backed where available, non-exportable, and never
 * leaves the device. So the ciphertext persisted in DataStore (and anything that copies that
 * file — cloud backup, forensic pull) is useless without this device's Keystore.
 *
 * Hand-rolled rather than Jetpack Security's `EncryptedSharedPreferences`, which is deprecated.
 * No user-authentication requirement on the key: the app deliberately has no biometric/PIN gate
 * (owner removed it as unwanted friction), and the security win here is at-rest protection of the
 * credential file, not a per-read auth prompt.
 *
 * Wire format of the returned blob: base64( IV(12 bytes) || GCM ciphertext+tag ).
 *
 * Stateless (the key lives in the Keystore, not here), so [CredentialStore] just instantiates it —
 * no need to put it in the DI graph.
 */
class TokenCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt a token to the storable blob. Null in → null out (nothing to store). */
    fun encrypt(plaintext: String?): String? {
        if (plaintext == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv // 12-byte GCM nonce the provider generated
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /**
     * Decrypt a stored blob back to the token. Returns null on any failure (corrupt blob, or the
     * Keystore key was invalidated by a device security reset) — the caller then treats the app as
     * signed-out and the user re-enters the token, rather than crashing.
     */
    fun decrypt(blob: String?): String? {
        if (blob.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            if (bytes.size <= GCM_IV_LENGTH) return null
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hawksnest_ha_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
