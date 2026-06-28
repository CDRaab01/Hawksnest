package com.hawksnest

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the mock Home Assistant `/__scenario/…` control API — the same scriptable backend
 * the web Playwright E2E suite drives (see `mock-ha/README.md`). The instrumented tests run *on the
 * emulator*, so the host's mock server is reached through the standard host-loopback alias
 * `10.0.2.2`. Pure JDK + `org.json` (bundled in Android) so it pulls in no extra test dependencies.
 */
class MockControl(private val base: String) {

    /** One recorded `call_service`, flattened for round-trip assertions. */
    data class ServiceCall(val domain: String, val service: String, val entityId: String?)

    fun health(): Boolean =
        runCatching { get("/__scenario/health").contains("\"ok\":true") }.getOrDefault(false)

    /** Load a named scenario fresh (clears the recorded call log + pushes state to live clients). */
    fun reset(scenario: String = "default") =
        post("/__scenario/reset", JSONObject().put("scenario", scenario))

    /** Push one entity state change over the live subscription. */
    fun pushState(entityId: String, state: String) =
        post("/__scenario/state", JSONObject().put("entity_id", entityId).put("state", state))

    /** Script how the next matching `call_service` resolves (e.g. `jammed`). */
    fun serviceOutcome(domain: String, service: String, outcome: String, entityId: String? = null) =
        post(
            "/__scenario/service-outcome",
            JSONObject().put("domain", domain).put("service", service).put("outcome", outcome)
                .apply { entityId?.let { put("entity_id", it) } },
        )

    /** The recorded `call_service` log. */
    fun calls(): List<ServiceCall> {
        val arr = JSONArray(get("/__scenario/calls"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ServiceCall(
                domain = o.optString("domain"),
                service = o.optString("service"),
                entityId = targetEntityId(o.optJSONObject("target")),
            )
        }
    }

    private fun targetEntityId(target: JSONObject?): String? {
        val v = target?.opt("entity_id") ?: return null
        return when (v) {
            is JSONArray -> if (v.length() > 0) v.getString(0) else null
            else -> v.toString()
        }
    }

    private fun post(path: String, body: JSONObject): String =
        request("POST", path, body.toString())

    private fun get(path: String): String = request("GET", path, null)

    private fun request(method: String, path: String, body: String?): String {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        } finally {
            conn.disconnect()
        }
    }
}
