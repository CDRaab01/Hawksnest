package com.hawksnest.core.logic

import com.hawksnest.core.ha.HistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `motionBlocks.ts` behavior. */
class SensorBlocksTest {

    private val T0 = 1_700_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private fun pt(offsetMin: Long, state: String) = HistoryPoint(T0 + min(offsetMin), state)

    @Test
    fun `folds an on-off run into one block with real start and end`() {
        val blocks = motionBlocksFromHistory(
            listOf(pt(0, "off"), pt(10, "on"), pt(12, "off")),
            "front",
        )
        assertEquals(1, blocks.size)
        assertEquals(T0 + min(10), blocks[0].startMs)
        assertEquals(T0 + min(12), blocks[0].endMs)
        assertEquals("motion", blocks[0].label)
        assertEquals("front", blocks[0].camera)
        assertTrue(!blocks[0].hasClip)
    }

    @Test
    fun `separate runs beyond the merge gap stay separate`() {
        // Two motions 5 min apart (> default 1 min gap) → two blocks.
        val blocks = motionBlocksFromHistory(
            listOf(pt(0, "on"), pt(1, "off"), pt(6, "on"), pt(7, "off")),
            "front",
        )
        assertEquals(2, blocks.size)
        assertEquals(listOf(T0 + min(0), T0 + min(6)), blocks.map { it.startMs })
    }

    @Test
    fun `flicker within the merge gap coalesces into one block`() {
        // off for 30s (< 1 min) between two runs → one merged block spanning both.
        val blocks = motionBlocksFromHistory(
            listOf(
                HistoryPoint(T0, "on"),
                HistoryPoint(T0 + 20_000, "off"),
                HistoryPoint(T0 + 40_000, "on"),
                HistoryPoint(T0 + 60_000, "off"),
            ),
            "front",
        )
        assertEquals(1, blocks.size)
        assertEquals(T0, blocks[0].startMs)
        assertEquals(T0 + 60_000, blocks[0].endMs)
    }

    @Test
    fun `a run still on at the end of history is left ongoing`() {
        val blocks = motionBlocksFromHistory(listOf(pt(0, "off"), pt(5, "on")), "front")
        assertEquals(1, blocks.size)
        assertEquals(T0 + min(5), blocks[0].startMs)
        assertNull(blocks[0].endMs)
    }

    @Test
    fun `empty or all-off history yields no blocks`() {
        assertTrue(motionBlocksFromHistory(emptyList(), "front").isEmpty())
        assertTrue(
            motionBlocksFromHistory(listOf(pt(0, "off"), pt(5, "unavailable")), "front").isEmpty(),
        )
    }

    @Test
    fun `points are sorted defensively before folding`() {
        val blocks = motionBlocksFromHistory(
            listOf(pt(12, "off"), pt(10, "on"), pt(0, "off")),
            "front",
        )
        assertEquals(1, blocks.size)
        assertEquals(T0 + min(10), blocks[0].startMs)
        assertEquals(T0 + min(12), blocks[0].endMs)
    }

    @Test
    fun `mergePlayable marks the block containing a clip and swaps its id`() {
        val blocks = motionBlocksFromHistory(listOf(pt(10, "on"), pt(12, "off")), "front")
        val clip = CameraEvent(
            id = "Motion 1", camera = "front", label = "motion",
            startMs = T0 + min(11), endMs = null,
            hasClip = true, hasSnapshot = false, thumbnailUrl = null, snapshotUrl = null,
        )
        val merged = mergePlayable(blocks, listOf(clip))
        assertEquals(1, merged.size)
        assertEquals("Motion 1", merged[0].id)
        assertTrue(merged[0].hasClip)
        // The block keeps its recovered duration, only borrowing the clip's playable handle.
        assertEquals(T0 + min(10), merged[0].startMs)
        assertEquals(T0 + min(12), merged[0].endMs)
    }

    @Test
    fun `mergePlayable appends clips that fall in no block, oldest-first`() {
        val blocks = motionBlocksFromHistory(listOf(pt(10, "on"), pt(12, "off")), "front")
        val far = CameraEvent(
            id = "Motion 9", camera = "front", label = "motion",
            startMs = T0 + min(100), endMs = null,
            hasClip = true, hasSnapshot = false, thumbnailUrl = null, snapshotUrl = null,
        )
        val merged = mergePlayable(blocks, listOf(far))
        assertEquals(2, merged.size)
        assertEquals(listOf(T0 + min(10), T0 + min(100)), merged.map { it.startMs })
        // The block stayed a non-playable marker; the far clip stayed playable.
        assertTrue(!merged[0].hasClip)
        assertTrue(merged[1].hasClip)
    }
}
