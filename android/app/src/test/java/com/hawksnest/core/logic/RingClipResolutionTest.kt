package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recorded-clip path's one genuinely subtle rule. Getting it wrong does not throw or show an
 * error — it plays the *wrong moment's* video, convincingly, which is the worst possible failure
 * mode for a security camera and the reason this is pinned.
 */
class RingClipResolutionTest {

    private fun selector(state: String, recordingUrl: String? = null): HassEntity =
        HassEntity(
            entityId = "select.front_door_event_select",
            state = state,
            attributes = buildJsonObject {
                if (recordingUrl != null) put("recordingUrl", recordingUrl)
            },
        )

    private val OLD = "https://ring.example/clip-old.mp4"
    private val NEW = "https://ring.example/clip-new.mp4"

    @Test
    fun `waits while the selector still shows the previous option`() {
        val r = ringClipReading(selector("Motion 2", NEW), "Motion 1", false, OLD)
        assertEquals(RingClipReading.Wait, r)
    }

    @Test
    fun `waits when the option is right but no URL has been published yet`() {
        val r = ringClipReading(selector("Motion 1"), "Motion 1", false, OLD)
        assertEquals(RingClipReading.Wait, r)
    }

    @Test
    fun `waits through the window where state updated but the URL has not`() {
        // The load-bearing case. ring-mqtt publishes state and attributes together; HA delivers
        // them as two updates. Accepting the URL here plays the PREVIOUS clip while the UI says
        // the new one is playing — no error, no clue.
        val r = ringClipReading(selector("Motion 1", OLD), "Motion 1", false, OLD)
        assertEquals(RingClipReading.Wait, r)
    }

    @Test
    fun `ready once the URL has actually changed`() {
        val r = ringClipReading(selector("Motion 1", NEW), "Motion 1", false, OLD)
        assertEquals(RingClipReading.Ready(NEW), r)
    }

    @Test
    fun `an already-selected option accepts its published URL unchanged`() {
        // Re-selecting the active option will never change the URL, so requiring a change would
        // hang until the timeout. Also covers two selector options mapping to one Ring event.
        val r = ringClipReading(selector("Motion 1", OLD), "Motion 1", true, OLD)
        assertEquals(RingClipReading.Ready(OLD), r)
    }

    @Test
    fun `first-ever selection is ready with no previous URL to compare against`() {
        val r = ringClipReading(selector("Motion 1", NEW), "Motion 1", false, null)
        assertEquals(RingClipReading.Ready(NEW), r)
    }

    @Test
    fun `Recording Not Found is terminal, not something to wait out`() {
        // The event rotated out of Ring's ~5-slot history. Failing now beats burning the full
        // 20s timeout on something that will never arrive.
        val r = ringClipReading(
            selector("Motion 1", "<Recording Not Found>"), "Motion 1", false, OLD,
        )
        assertEquals(RingClipReading.Missing, r)
    }

    @Test
    fun `transcoding in progress keeps waiting`() {
        // Not a URL and not the terminal marker — it resolves into a real URL shortly.
        val r = ringClipReading(
            selector("Motion 1", "<Transcoding in Progress>"), "Motion 1", false, OLD,
        )
        assertEquals(RingClipReading.Wait, r)
    }

    @Test
    fun `a missing selector entity waits rather than failing`() {
        // Entities arrive incrementally after a reconnect; absence is not an answer.
        assertEquals(RingClipReading.Wait, ringClipReading(null, "Motion 1", false, OLD))
    }

    @Test
    fun `a non-http attribute value is not treated as playable`() {
        val r = ringClipReading(selector("Motion 1", "pending"), "Motion 1", false, null)
        assertEquals(RingClipReading.Wait, r)
    }
}
