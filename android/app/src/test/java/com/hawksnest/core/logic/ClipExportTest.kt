package com.hawksnest.core.logic

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parity contract for clip export. `src/lib/__tests__/clipExport.test.ts` carries the *same
 * cases with the same numbers* — the two files are meant to be diffable. If you change an
 * assertion here, change it there, or the platforms have silently forked.
 */
class ClipExportTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    /** A 3-day retention window padded past now, as the timeline actually supplies it. */
    private val window = TimeWindow(now - 3 * day, now + 1_800_000L)
    private val b = exportBounds(window, now)

    private fun span(startMs: Long, endMs: Long, playable: Boolean = true) =
        FootageSpan(startMs, endMs, playable)

    // ---- exportBounds ----

    @Test
    fun `exportBounds stops one segment short of now`() {
        assertEquals(now - EXPORT_TAIL_LAG_MS, exportBounds(window, now).endMs)
    }

    @Test
    fun `exportBounds keeps the retention floor and never inverts`() {
        assertEquals(window.startMs, exportBounds(window, now).startMs)
        val tiny = TimeWindow(now, now)
        assertEquals(now, exportBounds(tiny, now).endMs)
    }

    @Test
    fun `exportBounds honours a window ending before the tail lag would`() {
        val early = TimeWindow(now - day, now - 10 * 60_000L)
        assertEquals(early.endMs, exportBounds(early, now).endMs)
    }

    // ---- defaultSelection ----

    @Test
    fun `defaultSelection opens centred on the playhead`() {
        val head = now - day
        assertEquals(
            ClipSelection(head - DEFAULT_HALF_MS, head + DEFAULT_HALF_MS),
            defaultSelection(head, b),
        )
    }

    @Test
    fun `defaultSelection slides inside the bounds rather than shrinking at an edge`() {
        val sel = defaultSelection(b.endMs, b)
        assertEquals(b.endMs, sel.endMs)
        assertEquals(2 * DEFAULT_HALF_MS, selectionDurationMs(sel))
    }

    // ---- clampSelection ----

    @Test
    fun `clampSelection grows a too-short range to the minimum`() {
        val sel = clampSelection(ClipSelection(now - day, now - day + 500L), b)
        assertEquals(MIN_CLIP_MS, selectionDurationMs(sel))
    }

    @Test
    fun `clampSelection trims a too-long range to the maximum`() {
        val sel = clampSelection(ClipSelection(now - day, now - day + 3 * MAX_CLIP_MS), b)
        assertEquals(MAX_CLIP_MS, selectionDurationMs(sel))
    }

    @Test
    fun `clampSelection moves the edge the user is NOT holding`() {
        val start = now - day
        val over = ClipSelection(start, start + 3 * MAX_CLIP_MS)
        assertEquals(
            ClipSelection(start, start + MAX_CLIP_MS),
            clampSelection(over, b, ClipEdge.START),
        )
        assertEquals(
            ClipSelection(over.endMs - MAX_CLIP_MS, over.endMs),
            clampSelection(over, b, ClipEdge.END),
        )
    }

    @Test
    fun `clampSelection never returns an inverted range`() {
        val sel = clampSelection(ClipSelection(now - day, now - 2 * day), b)
        assertTrue(sel.endMs >= sel.startMs)
    }

    @Test
    fun `clampSelection collapses when the bounds are narrower than a minimum clip`() {
        val narrow = TimeWindow(now, now + 1_000L)
        assertEquals(narrow.startMs, clampSelection(ClipSelection(now - day, now + day), narrow).startMs)
        assertEquals(narrow.endMs, clampSelection(ClipSelection(now - day, now + day), narrow).endMs)
    }

    // ---- setEdge ----

    @Test
    fun `setEdge keeps the moved edge exactly where it was put`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        val target = now - day + 20_000L
        assertEquals(target, setEdge(sel, ClipEdge.START, target, b).startMs)
        assertEquals(target, setEdge(sel, ClipEdge.END, target, b).endMs)
    }

    @Test
    fun `setEdge pins rather than swaps when dragged past the other edge`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        val dragged = setEdge(sel, ClipEdge.START, sel.endMs + 60_000L, b)
        assertTrue(dragged.startMs < dragged.endMs)
        assertEquals(MIN_CLIP_MS, selectionDurationMs(dragged))
    }

    @Test
    fun `setEdge cannot place an edge past the export ceiling`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        assertEquals(b.endMs, setEdge(sel, ClipEdge.END, now + day, b).endMs)
    }

    @Test
    fun `setEdge cannot place an edge before retention`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        assertEquals(b.startMs, setEdge(sel, ClipEdge.START, b.startMs - day, b).startMs)
    }

    // ---- nudge ----

    @Test
    fun `nudge shifts one edge and leaves the other alone`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        val out = nudge(sel, ClipEdge.END, 15_000L, b)
        assertEquals(sel.endMs + 15_000L, out.endMs)
        assertEquals(sel.startMs, out.startMs)
    }

    @Test
    fun `nudge refuses to go below the minimum duration`() {
        val sel = ClipSelection(now - day, now - day + MIN_CLIP_MS)
        assertEquals(MIN_CLIP_MS, selectionDurationMs(nudge(sel, ClipEdge.END, -60_000L, b)))
    }

    // ---- pickHandle ----

    @Test
    fun `pickHandle returns null when near neither handle`() {
        assertNull(pickHandle(500f, 100f, 200f))
    }

    @Test
    fun `pickHandle grabs whichever handle is within slop`() {
        assertEquals(ClipEdge.START, pickHandle(100f + HANDLE_HIT_SLOP_PX - 1f, 100f, 400f))
        assertEquals(ClipEdge.END, pickHandle(400f - HANDLE_HIT_SLOP_PX + 1f, 100f, 400f))
    }

    @Test
    fun `pickHandle prefers the nearer handle and breaks a tie towards end`() {
        assertEquals(ClipEdge.START, pickHandle(105f, 100f, 130f))
        assertEquals(ClipEdge.END, pickHandle(115f, 100f, 130f))
        assertEquals(ClipEdge.END, pickHandle(100f, 100f, 100f))
    }

    // ---- coverage ----

    private val covSel = ClipSelection(now - day, now - day + 60_000L)

    @Test
    fun `coverage reports unknown - not none - when there is no lane data`() {
        // The lane is absent while the fetch is in flight and on sources that don't provide one.
        // Reporting NONE there would permanently disable export on a transient failure.
        assertEquals(ClipCoverage.UNKNOWN, coverage(covSel, emptyList()))
    }

    @Test
    fun `coverage reports full when a span encloses the selection`() {
        val spans = listOf(span(covSel.startMs - 60_000L, covSel.endMs + 60_000L))
        assertEquals(ClipCoverage.FULL, coverage(covSel, spans))
    }

    @Test
    fun `coverage reports none when no span overlaps`() {
        val spans = listOf(span(now - 2 * day, now - 2 * day + 60_000L))
        assertEquals(ClipCoverage.NONE, coverage(covSel, spans))
    }

    @Test
    fun `coverage reports partial when the selection straddles a gap`() {
        val spans =
            listOf(
                span(covSel.startMs - 60_000L, covSel.startMs + 15_000L),
                span(covSel.endMs - 15_000L, covSel.endMs + 60_000L),
            )
        assertEquals(ClipCoverage.PARTIAL, coverage(covSel, spans))
    }

    @Test
    fun `coverage does not count footage that cannot be decoded`() {
        val spans = listOf(span(covSel.startMs, covSel.endMs, playable = false))
        assertEquals(ClipCoverage.NONE, coverage(covSel, spans))
    }

    // ---- selectionProblem ----

    private val covered = listOf(span(now - 3 * day, now))

    @Test
    fun `selectionProblem passes a well-formed covered selection`() {
        assertNull(selectionProblem(ClipSelection(now - day, now - day + 60_000L), covered))
    }

    @Test
    fun `selectionProblem rejects durations outside the limits`() {
        assertEquals(
            ClipProblem.TOO_SHORT,
            selectionProblem(ClipSelection(now - day, now - day + 1_000L), covered),
        )
        assertEquals(
            ClipProblem.TOO_LONG,
            selectionProblem(ClipSelection(now - day, now - day + MAX_CLIP_MS + 1_000L), covered),
        )
    }

    @Test
    fun `selectionProblem rejects a range with no footage behind it`() {
        assertNull(selectionProblem(ClipSelection(now - day, now - day + 60_000L), emptyList()))
        assertEquals(
            ClipProblem.NO_FOOTAGE,
            selectionProblem(
                ClipSelection(now - day, now - day + 60_000L),
                listOf(span(now - 2 * day, now - 2 * day + 1_000L)),
            ),
        )
    }

    @Test
    fun `selectionProblem allows a partially covered range`() {
        val sel = ClipSelection(now - day, now - day + 60_000L)
        assertNull(selectionProblem(sel, listOf(span(sel.startMs, sel.startMs + 20_000L))))
    }

    // ---- clipRangeSeconds ----

    @Test
    fun `clipRangeSeconds rounds outwards`() {
        assertEquals(ClipRange(1_000L, 2_001L), clipRangeSeconds(ClipSelection(1_000_400L, 2_000_100L)))
    }

    @Test
    fun `clipRangeSeconds leaves whole seconds untouched`() {
        assertEquals(ClipRange(1_000L, 2_000L), clipRangeSeconds(ClipSelection(1_000_000L, 2_000_000L)))
    }

    // ---- clipFileName ----

    private fun localMs(y: Int, mon: Int, d: Int, h: Int, min: Int, s: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(y, mon, d, h, min, s)
        return cal.timeInMillis
    }

    @Test
    fun `clipFileName stamps the clip's LOCAL start time and its length`() {
        val start = localMs(2026, 7, 9, 14, 3, 5)
        assertEquals(
            "big_room-2026-08-09-14-03-05-50s.mp4",
            clipFileName("big_room", ClipSelection(start, start + 50_000L)),
        )
    }

    @Test
    fun `clipFileName strips anything that has no business in a filename`() {
        val start = localMs(2026, 7, 9, 1, 2, 3)
        assertEquals(
            "front-door-2026-08-09-01-02-03-10s.mp4",
            clipFileName("../front door", ClipSelection(start, start + 10_000L)),
        )
    }
}
