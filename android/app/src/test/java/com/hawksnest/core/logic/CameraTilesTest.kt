package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraTilesTest {

    private val now = 1_800_000_000_000L

    private fun state(
        haAvailable: Boolean = true,
        asleep: Boolean = false,
        hasPicture: Boolean = true,
        failed: Boolean = false,
        at: Long? = now - 20_000,
    ) = tilePicture(haAvailable, asleep, hasPicture, failed, at, now)

    @Test
    fun `a recent picture is live`() {
        assertEquals(TilePicture.LIVE, state())
    }

    @Test
    fun `a picture older than the window is stale, not live`() {
        assertEquals(TilePicture.STALE, state(at = now - TILE_STALE_AFTER_MS - 1))
        assertEquals(TilePicture.LIVE, state(at = now - TILE_STALE_AFTER_MS))
    }

    @Test
    fun `a failed refresh over an old picture is stale, however recent the picture`() {
        assertEquals(TilePicture.STALE, state(failed = true, at = now - 5_000))
    }

    @Test
    fun `the ghost tile — HA says available but no picture ever loads — is no signal`() {
        assertEquals(TilePicture.NO_SIGNAL, state(hasPicture = false, failed = true, at = null))
        assertEquals(TilePicture.LOADING, state(hasPicture = false, failed = false, at = null))
    }

    @Test
    fun `asleep and unavailable win over whatever picture is showing`() {
        assertEquals(TilePicture.ASLEEP, state(asleep = true))
        assertEquals(TilePicture.NO_SIGNAL, state(haAvailable = false))
    }

    @Test
    fun `a picture with no known time still counts as live`() {
        assertEquals(TilePicture.LIVE, state(at = null))
    }

    @Test
    fun `the header counts each state and leaves loading out`() {
        val l = TilePicture.LIVE
        assertEquals("3 live", cameraCountLabel(listOf(l, l, l)))
        assertEquals(
            "9 live · 1 stale · 2 asleep · 1 no signal",
            cameraCountLabel(List(9) { l } + TilePicture.STALE + TilePicture.ASLEEP + TilePicture.ASLEEP + TilePicture.NO_SIGNAL),
        )
        assertEquals("2 live", cameraCountLabel(listOf(l, TilePicture.LOADING, l)))
        assertEquals("Loading", cameraCountLabel(listOf(TilePicture.LOADING, TilePicture.LOADING)))
        assertEquals("1 no signal", cameraCountLabel(listOf(TilePicture.NO_SIGNAL, TilePicture.LOADING)))
        assertEquals("", cameraCountLabel(emptyList()))
    }
}
