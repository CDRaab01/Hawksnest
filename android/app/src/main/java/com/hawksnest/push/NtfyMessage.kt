package com.hawksnest.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Raw ntfy stream envelope (the `/<topic>/json` line protocol). ntfy interleaves
 * control frames (`event` = "open" / "keepalive" / "poll_request") with actual
 * "message" frames; only the latter carry a payload worth a notification.
 */
@Serializable
data class NtfyEnvelope(
    val id: String = "",
    val event: String = "",
    val topic: String = "",
    val title: String? = null,
    val message: String? = null,
    val tags: List<String> = emptyList(),
    val priority: Int = 3,
    val click: String? = null,
)

/**
 * A ready-to-notify ntfy message. `parse` returns non-null only for a real
 * `event == "message"` frame with a body — keepalives, opens, and malformed
 * lines yield null so the service can skip them without a branch at the call site.
 */
data class NtfyMessage(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val priority: Int,
    val click: String?,
) {
    companion object {
        fun parse(line: String, json: Json): NtfyMessage? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed[0] != '{') return null
            val env = try {
                json.decodeFromString(NtfyEnvelope.serializer(), trimmed)
            } catch (e: Exception) {
                return null
            }
            if (env.event != "message") return null
            val body = env.message?.takeIf { it.isNotBlank() } ?: return null
            return NtfyMessage(
                id = env.id.ifBlank { body.hashCode().toString() },
                title = env.title?.takeIf { it.isNotBlank() } ?: "Hawksnest",
                body = body,
                tags = env.tags,
                priority = env.priority,
                click = env.click?.takeIf { it.isNotBlank() },
            )
        }
    }
}
