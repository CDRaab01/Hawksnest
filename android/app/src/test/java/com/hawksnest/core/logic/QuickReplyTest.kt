package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply URL is where this feature silently breaks.
 *
 * The source go2rtc needs contains `:`, `/` and a `#` fragment. Leave any of them raw and the
 * query is truncated at the `#` — go2rtc then receives a source with no codec directive, or none
 * at all, and either transcodes wrongly or fails. Neither is visible from the app: you tap Reply,
 * something happens on the server, and nobody speaks.
 */
class QuickReplyTest {

    @Test
    fun `the fragment is encoded, not left to truncate the query`() {
        val path = quickReplyPath("front_door", QUICK_REPLIES.first())
        assertFalse("a raw # ends the query at the codec directive", path.contains("#"))
        assertTrue("codec directive lost", path.contains("%23audio%3Dpcmu"))
    }

    @Test
    fun `path separators in the source are encoded`() {
        val path = quickReplyPath("garage", QuickReply("t", "t", "hello.wav"))
        assertTrue(path.contains("ffmpeg%3A%2Fconfig%2Freplies%2Fhello.wav"))
        // The query's own separators must survive as themselves.
        assertTrue(path.startsWith("go2rtc/api/streams?dst=garage&src="))
    }

    @Test
    fun `spaces never become plus`() {
        // URLEncoder would emit '+', which go2rtc reads as a literal plus in a filename.
        val path = quickReplyPath("front door", QuickReply("t", "t", "be right there.wav"))
        assertFalse("'+' is not a space here", path.contains("+"))
        assertTrue(path.contains("front%20door"))
        assertTrue(path.contains("be%20right%20there.wav"))
    }

    @Test
    fun `PCMU is requested, because that is what the backchannel advertises`() {
        // The cameras' SDP carries `a=rtpmap:0 PCMU/8000` on their sendonly track. Asking for
        // anything else makes go2rtc transcode at best and fail at worst.
        assertTrue(quickReplyPath("nursery", QUICK_REPLIES[1]).contains("audio%3Dpcmu"))
    }

    @Test
    fun `replies are few, short, and do not claim the house is empty`() {
        assertTrue("a door-step menu should stay glanceable", QUICK_REPLIES.size <= 4)
        for (r in QUICK_REPLIES) {
            assertTrue("${r.id} is too long to read at a door", r.label.length <= 45)
            // A stranger hears these. None should confirm nobody is home.
            for (giveaway in listOf("not home", "away", "nobody is home", "out of town")) {
                assertFalse("${r.id} tells a stranger the house is empty", r.label.lowercase().contains(giveaway))
            }
        }
    }

    @Test
    fun `every reply has a distinct id and file`() {
        assertEquals(QUICK_REPLIES.size, QUICK_REPLIES.map { it.id }.toSet().size)
        assertEquals(QUICK_REPLIES.size, QUICK_REPLIES.map { it.file }.toSet().size)
    }

    @Test
    fun `the speaker gate fails closed`() {
        assertTrue(canPlayReplies(true))
        assertFalse(canPlayReplies(false))
        // Unknown — the go2rtc stream list is still in flight. No button rather than a button
        // that might do nothing.
        assertFalse(canPlayReplies(null))
    }
}
