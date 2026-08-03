package com.hawksnest.core.logic

/**
 * A captured crash, in the shape that gets written to disk and later published.
 *
 * [whenMs] is when it happened, not when it was sent — a crash is almost always reported on the
 * *next* launch (see `CrashReporter`), so the send time would be misleading.
 */
data class CrashReport(
    val whenMs: Long,
    val threadName: String,
    val type: String,
    val message: String,
    val stack: String,
    val appVersion: String,
    val androidRelease: String,
    val device: String,
)

/**
 * Redact anything credential-shaped before a crash report is written or sent.
 *
 * This is the load-bearing part of crash reporting in this app, and the reason the formatting
 * lives in `core/logic` where it can be tested rather than inline in the handler.
 *
 * A stack trace is attacker-adjacent data: it gets written to disk, shown in Settings, and POSTed
 * to an ntfy topic that anyone who knows the topic name can subscribe to. Hawksnest holds a Home
 * Assistant long-lived token that opens the front door, plus RTSP camera credentials. An exception
 * message is exactly the kind of place a URL with an embedded credential ends up — OkHttp puts the
 * request URL in its IOException messages, and the RTSP tier builds
 * `rtsp://user:pass@camera-ip/...` by hand.
 *
 * So this scrubs by SHAPE rather than by knowing the specific secrets: the store isn't reachable
 * from here, the values rotate, and a scrubber that has to be told each secret fails the first
 * time someone adds one.
 */
fun scrubSecrets(text: String): String {
    var out = text
    // userinfo in any URL: rtsp://user:pass@host, https://u:p@host. Keep the scheme and host so
    // the trace still says which camera failed.
    out = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@""").replace(out) { "${it.groupValues[1]}***:***@" }
    // JWT-shaped tokens (the HA long-lived token is one) wherever they appear.
    out = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(\.[A-Za-z0-9_-]+)?""").replace(out, "eyJ***REDACTED***")
    // Authorization headers and query-string tokens.
    out = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{12,}""").replace(out) { "${it.groupValues[1]}***REDACTED***" }
    out = Regex("""(?i)([?&](?:token|access_token|api_key|apikey|password|signature|authSig)=)[^&\s"']+""")
        .replace(out) { "${it.groupValues[1]}***REDACTED***" }
    return out
}

/** The on-disk / on-screen form. Plain text on purpose: it has to be readable in a notification. */
fun formatCrashReport(r: CrashReport, isoTime: String): String = buildString {
    appendLine("Hawksnest crash")
    appendLine("when:    $isoTime")
    appendLine("app:     ${r.appVersion}")
    appendLine("device:  ${r.device} (Android ${r.androidRelease})")
    appendLine("thread:  ${r.threadName}")
    appendLine("type:    ${r.type}")
    if (r.message.isNotBlank()) appendLine("message: ${r.message}")
    appendLine()
    append(r.stack)
}.let(::scrubSecrets)

/**
 * The one-line summary for the ntfy notification body.
 *
 * A push notification is a glance, not a report — it answers "did it crash, and roughly where?"
 * The full trace is in the app under Settings. Truncated so a pathological message can't produce
 * a notification the system silently drops.
 */
fun crashNotificationLine(r: CrashReport): String {
    val where = r.stack.lineSequence()
        .map { it.trim() }
        // First frame that is ours — a trace's top frames are usually framework code, which
        // tells you nothing about which of OUR changes broke.
        .firstOrNull { it.startsWith("at com.hawksnest.") }
        ?.removePrefix("at ")
        ?: r.stack.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("at ") }?.removePrefix("at ")
        ?: "unknown location"
    val msg = r.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return scrubSecrets("${r.type.substringAfterLast('.')}$msg — $where").take(MAX_NOTIFICATION_CHARS)
}

private const val MAX_NOTIFICATION_CHARS = 300

/** Keep the most recent [keep] reports, newest first. Bounded so a crash loop can't fill storage. */
fun <T> trimToMostRecent(items: List<T>, keep: Int = MAX_STORED_CRASHES): List<T> =
    items.take(keep.coerceAtLeast(0))

/**
 * Ten is enough to see a pattern and small enough that a tight crash loop cannot fill the disk.
 * A crash loop writes one file per launch, so this is also the blast radius of a boot loop.
 */
const val MAX_STORED_CRASHES = 10
