package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "One second a day" — the 1:1 port of `src/lib/everyday.ts` (types + pure helpers), plus the
 * demo fixture. The `everyday` service (sibling repo `hawksnest-automation`, nginx `/everyday/`)
 * owns all selection logic; the app only lists days, plays candidates and posts a manual pick.
 */
data class EverydayCandidate(
    val eventId: String,
    val camera: String,
    val label: String,
    val subLabel: String?,
    /** Epoch seconds (Frigate's unit), as the service sends them. */
    val start: Double,
    val end: Double,
    val peak: Double,
    val description: String,
    val aiScore: Double?,
    val final: Double,
    /** Relative to `/everyday/`, e.g. `media/candidates/2026-10-26/<id>.mp4`. */
    val clip: String,
    val thumb: String,
)

data class EverydayDay(
    val date: String,
    val provisional: Boolean,
    val eventCount: Int,
    val genai: Boolean,
    val candidates: List<EverydayCandidate>,
    val pick: String?,
    val manual: Boolean,
)

data class EverydaySummary(
    val date: String,
    val provisional: Boolean,
    val manual: Boolean,
    val candidateCount: Int,
    val pick: EverydayCandidate?,
)

/** [label] is the service's "Edited" / "Every pick"; [edit] marks the watchable cut. */
data class EverydayReel(val year: Int, val days: Int, val url: String, val label: String = "", val edit: Boolean = false)

// ── parsing (lenient: a missing/odd field degrades, never throws) ───────────────────────────

private fun JsonObject.str(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

fun parseEverydayCandidate(o: JsonElement?): EverydayCandidate? {
    val j = o as? JsonObject ?: return null
    val id = j.str("eventId") ?: return null
    return EverydayCandidate(
        eventId = id,
        camera = j.str("camera") ?: "",
        label = j.str("label") ?: "",
        subLabel = j.str("subLabel"),
        start = j.num("start") ?: 0.0,
        end = j.num("end") ?: 0.0,
        peak = j.num("peak") ?: j.num("start") ?: 0.0,
        description = j.str("description") ?: "",
        aiScore = j.num("aiScore"),
        final = j.num("final") ?: 0.0,
        clip = j.str("clip") ?: "",
        thumb = j.str("thumb") ?: "",
    )
}

fun parseEverydayDay(j: JsonObject): EverydayDay = EverydayDay(
    date = j.str("date") ?: "",
    provisional = j.str("status") == "provisional",
    eventCount = (j["eventCount"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
    genai = j.bool("genai"),
    candidates = (j["candidates"] as? JsonArray)?.mapNotNull(::parseEverydayCandidate) ?: emptyList(),
    pick = j.str("pick"),
    manual = j.bool("manual"),
)

fun parseEverydayDays(j: JsonObject): List<EverydaySummary> =
    (j["days"] as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        EverydaySummary(
            date = o.str("date") ?: return@mapNotNull null,
            provisional = o.str("status") == "provisional",
            manual = o.bool("manual"),
            candidateCount = (o["candidateCount"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
            pick = o["pick"].takeUnless { it is JsonNull }?.let(::parseEverydayCandidate),
        )
    } ?: emptyList()

fun parseEverydayReels(j: JsonObject): List<EverydayReel> =
    (j["reels"] as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        EverydayReel(
            year = o.num("year")?.toInt() ?: return@mapNotNull null,
            days = o.num("days")?.toInt() ?: 0,
            url = o.str("url") ?: return@mapNotNull null,
            label = o.str("label") ?: "",
            edit = o.str("kind") == "edit",
        )
    } ?: emptyList()

// ── pure helpers (ported 1:1 with the web) ──────────────────────────────────────────────────

fun EverydayDay.pickCandidate(): EverydayCandidate? = candidates.firstOrNull { it.eventId == pick }

/** The candidate in the player: the one being previewed, else the pick, else the first. */
fun EverydayDay.shown(previewId: String?): EverydayCandidate? =
    candidates.firstOrNull { it.eventId == (previewId ?: pick) } ?: candidates.firstOrNull()

/** `front_door_reolink` -> `Front door reolink`. Display only. */
fun everydayCameraLabel(slug: String): String {
    val s = slug.replace('_', ' ').trim()
    return if (s.isEmpty()) slug else s.replaceFirstChar { it.uppercaseChar() }
}

/** `2026-10-26` -> `Mon, Oct 26`. A LOCAL date, never shifted by a zone. */
fun everydayDayLabel(date: String, locale: Locale = Locale.getDefault()): String = try {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
} catch (_: Exception) {
    date
}

fun everydayTimeLabel(epochSec: Double, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    Instant.ofEpochMilli((epochSec * 1000).toLong()).atZone(zone)
        .format(DateTimeFormatter.ofPattern("h:mm a", locale))

/** Consecutive picked days counting back from the newest summary. */
fun everydayStreak(days: List<EverydaySummary>): Int {
    var n = 0
    var expect: LocalDate? = null
    for (d in days.sortedByDescending { it.date }) {
        val cur = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: break
        if (expect != null && cur != expect) break
        if (d.pick == null) break
        n++
        expect = cur.minusDays(1)
    }
    return n
}

// ── demo fixture ────────────────────────────────────────────────────────────────────────────

/**
 * Sample data for demo mode (no HA token) — the "spoofed" service. Clips are [DEMO_CLIP_URI] so
 * the player shows real moving pixels from `R.raw.camera_loop`; thumbs are empty, which the
 * screen draws as a tinted placeholder.
 */
object EverydayFixture {
    private data class Moment(val camera: String, val label: String, val hour: Int, val min: Int, val desc: String, val ai: Double)

    private val MOMENTS = listOf(
        listOf(
            Moment("big_room", "person", 16, 12, "Two kids building a blanket fort between the couches.", 9.0),
            Moment("backyard_patio", "dog", 10, 41, "A dog chasing a ball across the leaf-covered yard.", 7.0),
            Moment("kitchen", "person", 18, 3, "Family gathered around the island making pizza.", 8.0),
            Moment("nursery", "person", 7, 55, "A parent lifting a baby out of the crib, morning light.", 8.0),
            Moment("front", "person", 15, 20, "A child arriving home with a backpack.", 5.0),
        ),
        listOf(
            Moment("kitchen", "person", 8, 15, "Pancake breakfast, someone flipping one high.", 8.0),
            Moment("big_room", "cat", 13, 2, "A cat batting at a ribbon on the rug.", 6.0),
            Moment("nursery_high", "person", 19, 40, "Bedtime story in the rocking chair.", 9.0),
            Moment("backyard_patio", "person", 11, 30, "Raking leaves into a pile, a kid jumping in.", 9.0),
        ),
        listOf(
            Moment("first_floor_stairway", "person", 7, 10, "A kid sliding down the last two stairs.", 6.0),
            Moment("big_room", "person", 20, 5, "Dancing in the living room.", 8.0),
            Moment("kitchen", "dog", 17, 45, "A dog waiting politely for a dropped carrot.", 7.0),
        ),
    )

    fun days(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), count: Int = 9): List<EverydayDay> =
        (0 until count).map { i ->
            val date = today.minusDays(i.toLong())
            val set = MOMENTS[i % MOMENTS.size]
            val cands = set.mapIndexed { k, m ->
                val t = date.atTime(m.hour, m.min).atZone(zone).toEpochSecond().toDouble()
                EverydayCandidate(
                    eventId = "demo-$date-$k",
                    camera = m.camera,
                    label = m.label,
                    subLabel = null,
                    start = t - 2,
                    end = t + 25,
                    peak = t,
                    description = m.desc,
                    aiScore = m.ai,
                    final = m.ai / 10,
                    clip = DEMO_CLIP_URI,
                    thumb = "",
                )
            }.sortedByDescending { it.final }
            EverydayDay(
                date = date.toString(),
                provisional = i == 0,
                eventCount = 40 + (i * 17) % 60,
                genai = true,
                candidates = cands,
                // Every third day the "user" swapped to the runner-up, to show the manual state.
                pick = if (i % 3 == 2) cands[1].eventId else cands[0].eventId,
                manual = i % 3 == 2,
            )
        }

    fun summaries(days: List<EverydayDay>): List<EverydaySummary> = days.map {
        EverydaySummary(it.date, it.provisional, it.manual, it.candidates.size, it.pickCandidate())
    }

    fun reels(days: List<EverydayDay>): List<EverydayReel> =
        days.groupBy { it.date.take(4).toInt() }.flatMap { (y, d) ->
            listOf(
                EverydayReel(y, d.size, DEMO_CLIP_URI, "Edited", edit = true),
                EverydayReel(y, d.size, DEMO_CLIP_URI, "Every pick"),
            )
        }
}
