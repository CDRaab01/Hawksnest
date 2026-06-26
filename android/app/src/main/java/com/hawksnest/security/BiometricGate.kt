package com.hawksnest.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The biometric chokepoint for "make the home *less* secure" actions (unlock a door, disarm the
 * alarm). Class-3 strong biometric with required confirmation (`setConfirmationRequired(true)`), so
 * a glance/tap can't fire it accidentally.
 *
 * If the device has no strong biometric enrolled, the action proceeds — the gate limits a
 * stolen-*unlocked*-phone blast radius, it isn't a second password and must never strand the owner
 * outside their own door. On auth error/cancel the action simply doesn't run.
 */
object BiometricGate {
    fun run(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
    ) {
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess() // no strong biometric available — don't lock the owner out
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(true)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }
}
