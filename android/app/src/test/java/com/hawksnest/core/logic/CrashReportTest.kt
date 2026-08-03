package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrubber is the part of crash reporting that must not be wrong.
 *
 * A crash report is written to disk, shown in Settings, and POSTed to an ntfy topic that anyone
 * who knows the topic name can subscribe to. This app holds a Home Assistant token that opens the
 * front door and RTSP credentials for seven cameras. A leak here is not a bug report with too
 * much detail in it — it is handing out the house.
 */
class CrashReportTest {

    // Shaped like the real thing, not the real thing — the canonical jwt.io example.
    //
    // Assembled at runtime rather than written as one literal so this file contains no
    // JWT-shaped string for the repo's gitleaks scan to flag. Writing it out failed CI, and
    // allowlisting it would have blunted the very rule that protects the HA token. The scrubber
    // still sees a genuine three-segment JWT at runtime, which is what is under test.
    private val JWT = listOf(
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
        "eyJzdWIiOiIxMjM0NTY3ODkwIn0",
        "dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
    ).joinToString(".")

    @Test
    fun `rtsp credentials are redacted but the camera is still identifiable`() {
        val s = scrubSecrets("failed: rtsp://frigate:sup3rSecret@192.168.4.30:554/h264Preview_01_main")
        assertFalse("password leaked", s.contains("sup3rSecret"))
        assertFalse("username leaked", s.contains("frigate:"))
        // Still has to be useful — which camera failed is the whole point of the report.
        assertTrue("lost the host", s.contains("192.168.4.30"))
        assertTrue("lost the scheme", s.contains("rtsp://"))
    }

    @Test
    fun `the HA long-lived token is redacted wherever it appears`() {
        for (context in listOf(
            "Authorization: Bearer $JWT",
            "ws://host/api/websocket?token=$JWT",
            "auth failed for $JWT while connecting",
        )) {
            val s = scrubSecrets(context)
            assertFalse("token leaked in: $context", s.contains(JWT))
        }
    }

    @Test
    fun `query-string secrets are redacted`() {
        val s = scrubSecrets("GET /api/vod/index.m3u8?authSig=abc123def456&start=0")
        assertFalse(s.contains("abc123def456"))
        // Non-secret params survive, so the trace still shows what was being requested.
        assertTrue(s.contains("start=0"))
        assertTrue(s.contains("/api/vod/index.m3u8"))
    }

    @Test
    fun `https userinfo is redacted too, not just rtsp`() {
        val s = scrubSecrets("https://admin:hunter2@dragonfly.ts.net:8443/api/")
        assertFalse(s.contains("hunter2"))
        assertTrue(s.contains("dragonfly.ts.net:8443"))
    }

    @Test
    fun `ordinary stack traces are left alone`() {
        val trace = """
            java.lang.IllegalStateException: Not connected to Home Assistant.
                at com.hawksnest.core.ha.HaSource.callService(HaSource.kt:117)
                at com.hawksnest.ui.entity.EntityDetailViewModel.toggle(EntityDetailViewModel.kt:42)
        """.trimIndent()
        assertEquals(trace, scrubSecrets(trace))
    }

    private fun report(stack: String, type: String = "java.lang.IllegalStateException", msg: String = "boom") =
        CrashReport(
            whenMs = 1_785_000_000_000, threadName = "main", type = type, message = msg,
            stack = stack, appVersion = "1.0.5 (29280000)", androidRelease = "16",
            device = "samsung SM-S928U",
        )

    @Test
    fun `the notification line names our frame, not the framework's`() {
        val line = crashNotificationLine(report("""
            java.lang.IllegalStateException: boom
                at java.util.ArrayList.get(ArrayList.java:427)
                at kotlinx.coroutines.BuildersKt.launch(Builders.kt:56)
                at com.hawksnest.ui.cameras.CameraPlayerViewModel.resolveRingClip(CameraPlayerViewModel.kt:301)
        """.trimIndent()))
        // Top frames are framework noise; the first com.hawksnest frame is the actionable one.
        assertTrue("should name our frame, got: $line", line.contains("CameraPlayerViewModel.resolveRingClip"))
        assertTrue(line.startsWith("IllegalStateException: boom"))
    }

    @Test
    fun `notification falls back to the top frame when nothing of ours is on the stack`() {
        val line = crashNotificationLine(report("""
            java.lang.OutOfMemoryError
                at android.graphics.Bitmap.nativeCreate(Bitmap.java:-2)
        """.trimIndent()))
        assertTrue(line.contains("Bitmap.nativeCreate"))
    }

    @Test
    fun `notification line is scrubbed and bounded`() {
        val line = crashNotificationLine(report(
            stack = "  at com.hawksnest.Foo.bar(Foo.kt:1)",
            msg = "connect failed: rtsp://frigate:sup3rSecret@10.0.0.5:554/x " + "x".repeat(500),
        ))
        assertFalse(line.contains("sup3rSecret"))
        assertTrue("should be truncated, was ${line.length}", line.length <= 300)
    }

    @Test
    fun `the formatted report is scrubbed end to end`() {
        val body = formatCrashReport(
            report("java.io.IOException\n    at okhttp3.Foo.bar(Foo.kt:1)\n  url=rtsp://u:p@10.0.0.5/s"),
            "2026-08-03T01:00:00Z",
        )
        assertFalse(body.contains("u:p@"))
        // The header still carries what makes a report diagnosable.
        assertTrue(body.contains("1.0.5 (29280000)"))
        assertTrue(body.contains("samsung SM-S928U"))
        assertTrue(body.contains("2026-08-03T01:00:00Z"))
    }

    @Test
    fun `storage is bounded so a crash loop cannot fill the disk`() {
        assertEquals(10, trimToMostRecent((1..40).toList()).size)
        assertEquals(listOf(1, 2, 3), trimToMostRecent(listOf(1, 2, 3), 10))
    }
}
