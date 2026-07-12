package com.hawksnest.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented: exercises the REAL Android Keystore (not available on the JVM, so this can't be a
 * plain unit test — run it on the emulator harness, `scripts/android-emulator-test.sh`). Verifies
 * the HA-token at-rest cipher round-trips, produces ciphertext (not the plaintext), uses a fresh
 * IV per call, and fails closed (null) on tampered/garbage input.
 */
@RunWith(AndroidJUnit4::class)
class TokenCipherTest {

    private val cipher = TokenCipher()
    private val token = "abcdef0123456789.longlived.home-assistant-token"

    @Test
    fun encrypt_then_decrypt_round_trips() {
        val blob = cipher.encrypt(token)
        assertNotEquals(token, blob) // stored value is ciphertext, not the token
        assertEquals(token, cipher.decrypt(blob))
    }

    @Test
    fun each_encryption_uses_a_fresh_iv() {
        // GCM must never reuse an IV under the same key — two encrypts of the same token differ.
        assertNotEquals(cipher.encrypt(token), cipher.encrypt(token))
    }

    @Test
    fun null_and_garbage_decrypt_to_null_not_a_crash() {
        assertNull(cipher.encrypt(null))
        assertNull(cipher.decrypt(null))
        assertNull(cipher.decrypt(""))
        assertNull(cipher.decrypt("not-base64-!!!"))
        assertNull(cipher.decrypt("AAAA")) // valid base64 but too short for IV+tag
    }

    @Test
    fun a_tampered_blob_fails_authentication_and_returns_null() {
        val blob = cipher.encrypt(token)!!
        // Flip the last base64 char — GCM's auth tag must reject it.
        val tampered = blob.dropLast(1) + if (blob.last() == 'A') 'B' else 'A'
        assertNull(cipher.decrypt(tampered))
    }
}
