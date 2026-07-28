package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `ringFootage.ts` behavior — same cases, same expectations, so the platforms can't drift. */
class RingFootageTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun seg(
        startMs: Long = t0,
        endMs: Long = t0 + 10 * min,
        url: String? = "https://ring/footage.mp4",
        urlExpiresAtMs: Long? = t0 + 15 * min,
        encrypted: Boolean = false,
    ) = FootageSegment(startMs, endMs, url, urlExpiresAtMs, encrypted, chunked = true, dingId = null)

    private fun clip(id: String, startMs: Long, endMs: Long?) = CameraEvent(
        id = id,
        camera = "gate",
        label = "motion",
        startMs = startMs,
        endMs = endMs,
        hasClip = true,
        hasSnapshot = false,
        thumbnailUrl = null,
        snapshotUrl = null,
    )

    // ── parse ────────────────────────────────────────────────────────────────

    @Test
    fun `normalizes the service payload and reports the earliest expiry`() {
        val out = parseRingFootage(
            obj(
                """
                {"segments":[
                  {"startMs":${t0 + 20 * min},"endMs":${t0 + 30 * min},"url":"b","urlExpiresAtMs":${t0 + 40 * min}},
                  {"startMs":$t0,"endMs":${t0 + 10 * min},"url":"a","urlExpiresAtMs":${t0 + 15 * min},
                   "encrypted":false,"chunked":true,"dingId":"77"}
                ],"truncated":true}
                """.trimIndent(),
            ),
        )

        // Sorted oldest-first regardless of the order Ring returned them.
        assertEquals(listOf("a", "b"), out.segments.map { it.url })
        assertTrue(out.continuous)
        assertTrue(out.truncated)
        // The FIRST url to die drives the refresh, not the last.
        assertEquals(t0 + 15 * min, out.expiresAtMs)
        assertEquals("77", out.segments[0].dingId)
        assertTrue(out.segments[0].chunked)
    }

    @Test
    fun `drops unusable spans without losing the rest`() {
        val out = parseRingFootage(
            obj(
                """
                {"segments":[
                  {"startMs":$t0,"endMs":${t0 + 10 * min},"url":"keep"},
                  {"startMs":$t0,"endMs":$t0},
                  {"startMs":${t0 + min},"endMs":$t0},
                  {"startMs":"nope","endMs":${t0 + min}}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(1, out.segments.size)
        assertEquals("keep", out.segments[0].url)
    }

    @Test
    fun `keeps encrypted and URL-less spans - they are real coverage`() {
        val out = parseRingFootage(
            obj(
                """
                {"segments":[
                  {"startMs":$t0,"endMs":${t0 + 10 * min}},
                  {"startMs":${t0 + 20 * min},"endMs":${t0 + 30 * min},"url":"x","encrypted":true}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, out.segments.size)
        assertEquals(listOf(false, false), out.segments.map { it.isPlayable() })
        // Coverage exists even though none of it can play — the lane must still draw it.
        assertTrue(out.continuous)
    }

    @Test
    fun `reads a camera with no 24-7 track as not-continuous, not as an error`() {
        val out = parseRingFootage(obj("""{"segments":[],"continuous":false}"""))
        assertEquals(emptyList(), out.segments)
        assertEquals(false, out.continuous)
        assertNull(out.expiresAtMs)
        assertEquals(emptyList(), parseRingFootage(obj("{}")).segments)
    }

    // ── containment ──────────────────────────────────────────────────────────

    @Test
    fun `contains the start and excludes the end, so a seam belongs to exactly one segment`() {
        val a = seg(t0, t0 + 10 * min, url = "a")
        val b = seg(t0 + 10 * min, t0 + 20 * min, url = "b")
        assertEquals("a", footageSegmentAt(listOf(a, b), t0)?.url)
        assertEquals("a", footageSegmentAt(listOf(a, b), t0 + 10 * min - 1)?.url)
        // The boundary instant is b's, not both — a closed interval made the player's segment-keyed
        // effects thrash between two sources while scrubbing across the seam.
        assertEquals("b", footageSegmentAt(listOf(a, b), t0 + 10 * min)?.url)
    }

    @Test
    fun `returns null outside the covered spans`() {
        val a = seg(t0, t0 + 10 * min)
        assertNull(footageSegmentAt(listOf(a), t0 - 1))
        assertNull(footageSegmentAt(listOf(a), t0 + 10 * min))
        assertNull(footageSegmentAt(emptyList(), t0))
    }

    @Test
    fun `picks the latest-starting segment on a genuine overlap`() {
        val wide = seg(t0, t0 + 60 * min, url = "wide")
        val inner = seg(t0 + 5 * min, t0 + 15 * min, url = "inner")
        assertEquals("inner", footageSegmentAt(listOf(wide, inner), t0 + 10 * min)?.url)
    }

    @Test
    fun `clamps the in-segment offset into the span`() {
        val s = seg(t0, t0 + 10 * min)
        assertEquals(90_000L, offsetInSegmentMs(s, t0 + 90_000))
        assertEquals(0L, offsetInSegmentMs(s, t0 - 5_000))
        assertEquals(10 * min, offsetInSegmentMs(s, t0 + 99 * min))
    }

    // ── drawable spans ───────────────────────────────────────────────────────

    @Test
    fun `coalesces abutting segments so a stitch seam does not read as a gap`() {
        val spans = footageSpans(listOf(seg(t0, t0 + 10 * min), seg(t0 + 10 * min, t0 + 25 * min)))
        assertEquals(listOf(FootageSpan(t0, t0 + 25 * min, playable = true)), spans)
    }

    @Test
    fun `keeps a real gap as a real gap`() {
        val spans = footageSpans(listOf(seg(t0, t0 + 10 * min), seg(t0 + 40 * min, t0 + 50 * min)))
        assertEquals(2, spans.size)
    }

    @Test
    fun `never merges playable footage into an unplayable run`() {
        val spans = footageSpans(
            listOf(
                seg(t0, t0 + 10 * min),
                seg(t0 + 10 * min, t0 + 20 * min, encrypted = true),
                seg(t0 + 20 * min, t0 + 30 * min),
            ),
        )
        assertEquals(listOf(true, false, true), spans.map { it.playable })
    }

    // ── source choice ────────────────────────────────────────────────────────

    private val events = listOf(clip("m1", t0 + 5 * min, t0 + 6 * min))
    private val urls = mapOf("m1" to "https://ring/clip.mp4")

    @Test
    fun `plays the continuous track even where an event clip also covers the moment`() {
        // The whole window is one media source, so scrubbing seeks instead of re-initialising the
        // player per clip. The event stays drawn on the timeline as a marker either way.
        val out = chooseRecordedSource(
            headMs = t0 + 5 * min + 30_000,
            segments = listOf(seg(t0, t0 + 60 * min, url = "cont")),
            events = events,
            urls = urls,
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertEquals(RecordedSource.Footage("cont", 330_000L, seg(t0, t0 + 60 * min, url = "cont")), out)
    }

    @Test
    fun `falls back to the event clip where the continuous track has a gap`() {
        val out = chooseRecordedSource(
            headMs = t0 + 5 * min + 30_000,
            segments = listOf(seg(t0 + 40 * min, t0 + 50 * min)),
            events = events,
            urls = urls,
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertEquals(RecordedSource.Clip("https://ring/clip.mp4", 30_000L, events[0]), out)
    }

    @Test
    fun `uses the event clip rather than reporting encrypted when both cover the moment`() {
        val out = chooseRecordedSource(
            headMs = t0 + 5 * min + 30_000,
            segments = listOf(seg(t0, t0 + 60 * min, encrypted = true)),
            events = events,
            urls = urls,
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertTrue(out is RecordedSource.Clip)
    }

    @Test
    fun `reports encrypted only when there is nothing else to play`() {
        val out = chooseRecordedSource(
            headMs = t0 + 30 * min,
            segments = listOf(seg(t0, t0 + 60 * min, encrypted = true)),
            events = events,
            urls = urls,
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertEquals(RecordedSource.Encrypted, out)
    }

    @Test
    fun `distinguishes nothing-recorded from every other outcome`() {
        val out = chooseRecordedSource(
            headMs = t0 + 30 * min,
            segments = emptyList(),
            events = events,
            urls = urls,
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertEquals(RecordedSource.None, out)
    }

    @Test
    fun `will not play an event whose URL never came down with the timeline`() {
        val out = chooseRecordedSource(
            headMs = t0 + 5 * min + 30_000,
            segments = emptyList(),
            events = events,
            urls = emptyMap(),
            loadedClipId = null,
            loadedDurationMs = null,
        )
        assertEquals(RecordedSource.None, out)
    }

    @Test
    fun `resolves an open-ended clip's span from the loaded media duration`() {
        val openEnded = listOf(clip("m1", t0 + 5 * min, null))
        val at = t0 + 5 * min + 45_000
        // 30s is the assumed span, so without the loaded duration this moment is past the clip's end.
        assertEquals(
            RecordedSource.None,
            chooseRecordedSource(at, emptyList(), openEnded, urls, null, null),
        )
        assertEquals(
            RecordedSource.Clip("https://ring/clip.mp4", 45_000L, openEnded[0]),
            chooseRecordedSource(at, emptyList(), openEnded, urls, "m1", 90_000L),
        )
    }
}
