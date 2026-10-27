package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from `src/lib/__tests__/everyday.test.ts`, plus parsing and the demo fixture. */
class EverydayTest {

    private fun summary(date: String, picked: Boolean = true) = EverydaySummary(
        date = date, provisional = false, manual = false, candidateCount = 5,
        pick = if (picked) cand(date) else null,
    )

    private fun cand(id: String, camera: String = "kitchen") = EverydayCandidate(
        eventId = id, camera = camera, label = "person", subLabel = null, start = 0.0, end = 0.0,
        peak = 0.0, description = "", aiScore = null, final = 0.0, clip = "", thumb = "",
    )

    @Test
    fun `streak counts consecutive picked days back from the newest`() {
        assertEquals(3, everydayStreak(listOf(summary("2026-10-26"), summary("2026-10-25"), summary("2026-10-24"))))
        assertEquals(1, everydayStreak(listOf(summary("2026-10-26"), summary("2026-10-24"))))
        assertEquals(0, everydayStreak(listOf(summary("2026-10-26", picked = false), summary("2026-10-25"))))
        assertEquals(2, everydayStreak(listOf(summary("2026-10-31"), summary("2026-11-01"))))
        assertEquals(0, everydayStreak(emptyList()))
    }

    @Test
    fun `labels`() {
        assertEquals("Mon, Oct 26", everydayDayLabel("2026-10-26", Locale.US))
        assertEquals("Front door reolink", everydayCameraLabel("front_door_reolink"))
        assertEquals("garbage", everydayDayLabel("garbage", Locale.US))
        val noon = LocalDate.of(2026, 10, 26).atTime(12, 5).atZone(ZoneId.of("UTC")).toEpochSecond().toDouble()
        assertEquals("12:05 PM", everydayTimeLabel(noon, ZoneId.of("UTC"), Locale.US))
    }

    @Test
    fun `shown prefers preview, then pick, then first`() {
        val day = EverydayDay("2026-10-26", false, 1, true, listOf(cand("a"), cand("b")), pick = "b", manual = false)
        assertEquals("a", day.shown("a")?.eventId)
        assertEquals("b", day.shown(null)?.eventId)
        assertEquals("a", day.copy(pick = null).shown(null)?.eventId)
        assertNull(day.copy(candidates = emptyList()).shown(null))
    }

    @Test
    fun `parses the service JSON leniently`() {
        val days = Json.parseToJsonElement(
            """{"days":[{"date":"2026-10-26","status":"final","manual":true,"candidateCount":2,
               "pick":{"eventId":"e1","camera":"big_room","start":1.5,"end":9,"peak":3,"aiScore":9,
               "final":0.9,"clip":"media/c/e1.mp4","thumb":"media/c/e1.jpg","description":"fort"}},
               {"date":"2026-10-25","status":"provisional","pick":null},{"nodate":1}],"genai":true}""",
        ) as JsonObject
        val parsed = parseEverydayDays(days)
        assertEquals(2, parsed.size)
        assertEquals("e1", parsed[0].pick?.eventId)
        assertEquals(9.0, parsed[0].pick?.aiScore)
        assertTrue(parsed[0].manual)
        assertTrue(parsed[1].provisional)
        assertNull(parsed[1].pick)

        val day = parseEverydayDay(
            Json.parseToJsonElement(
                """{"date":"2026-10-26","status":"final","eventCount":42,"genai":false,
                   "candidates":[{"eventId":"a","camera":"kitchen"},{"camera":"no-id"}],"pick":"a","manual":false}""",
            ) as JsonObject,
        )
        assertEquals(42, day.eventCount)
        assertEquals(listOf("a"), day.candidates.map { it.eventId })
        assertEquals("a", day.pickCandidate()?.eventId)

        val reels = parseEverydayReels(
            Json.parseToJsonElement("""{"reels":[{"year":2026,"days":300,"url":"media/reel/everyday-2026.mp4"}]}""") as JsonObject,
        )
        assertEquals(listOf(EverydayReel(2026, 300, "media/reel/everyday-2026.mp4")), reels)
    }

    @Test
    fun `demo fixture is a believable, playable week`() {
        val today = LocalDate.of(2026, 10, 27)
        val days = EverydayFixture.days(today, ZoneId.of("America/Indiana/Indianapolis"))
        assertEquals("2026-10-27", days.first().date)
        assertTrue(days.first().provisional)
        days.forEach { d ->
            assertTrue(d.candidates.isNotEmpty())
            assertTrue(d.pickCandidate() != null, "every sample day has a pick")
            d.candidates.forEach { assertEquals(DEMO_CLIP_URI, it.clip) }
            // Candidate times fall on the sample day itself.
            d.candidates.forEach { c ->
                val local = java.time.Instant.ofEpochSecond(c.peak.toLong()).atZone(ZoneId.of("America/Indiana/Indianapolis")).toLocalDate()
                assertEquals(d.date, local.toString())
            }
        }
        assertTrue(days.any { it.manual }, "shows the manual-pick state too")
        assertEquals(days.size, everydayStreak(EverydayFixture.summaries(days)))
        assertEquals(listOf(2026), EverydayFixture.reels(days).map { it.year })
    }
}
