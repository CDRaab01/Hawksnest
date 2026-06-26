package com.hawksnest.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hawksnest.BuildConfig
import com.hawksnest.di.ApplicationScope
import com.hawksnest.util.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FCM enrollment — initializes the default [FirebaseApp] from BuildConfig (no google-services.json,
 * so the build is green without the owner's Firebase project) and writes the device token into HA's
 * `input_text.hawksnest_push_token` over authenticated REST. HA's automation reads that helper to
 * target the device. Push is **disabled** (a no-op) until all four FCM fields are configured.
 */
@Singleton
class FcmEnrollment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val credentialStore: CredentialStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** True only when the build carries a full FCM project config. */
    val configured: Boolean =
        BuildConfig.FCM_PROJECT_ID.isNotBlank() &&
            BuildConfig.FCM_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FCM_API_KEY.isNotBlank() &&
            BuildConfig.FCM_SENDER_ID.isNotBlank()

    /** Create the default FirebaseApp from BuildConfig. Idempotent; a no-op when unconfigured. */
    fun init() {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    /** Fetch the current token and push it to HA. Safe to call repeatedly (e.g. after connect). */
    fun enroll() {
        if (!configured) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> onNewToken(token) }
        }
    }

    /** Called by [HawksnestMessagingService] on token rotation. */
    fun onNewToken(token: String) {
        scope.launch { writeTokenToHa(token) }
    }

    private suspend fun writeTokenToHa(token: String) {
        val url = credentialStore.haUrl.firstOrNull()?.trimEnd('/')?.ifBlank { null } ?: return
        val haToken = credentialStore.haToken.firstOrNull()?.ifBlank { null } ?: return
        val payload = buildJsonObject {
            put("entity_id", "input_text.hawksnest_push_token")
            put("value", token)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$url/api/services/input_text/set_value")
            .addHeader("Authorization", "Bearer $haToken")
            .post(payload)
            .build()
        // Best-effort: a missing helper / offline HA shouldn't crash; HA echoes nothing we need.
        runCatching { okHttpClient.newCall(request).execute().use { } }
    }
}
