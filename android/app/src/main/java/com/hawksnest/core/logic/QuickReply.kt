package com.hawksnest.core.logic

/**
 * A prerecorded message that can be played out of a camera's speaker.
 *
 * [file] is a bare filename, not a path: the audio lives on go2rtc's own config volume so one set
 * serves every device and both clients, and so the phone never has to hold or upload audio.
 */
data class QuickReply(val id: String, val label: String, val file: String)

/**
 * The messages offered, in order.
 *
 * Deliberately short and few. This is a list you read while somebody is standing at the door, so
 * a scrolling menu of near-identical phrasings would be worse than three obvious ones. Ring ships
 * a comparable set for the same reason.
 *
 * Wording is neutral about whether anyone is home. "We'll be right there" and "nobody can come to
 * the door" both work whether the house is full or empty, which matters for a message a stranger
 * hears.
 *
 * The WAV files must exist on the go2rtc config volume at `/config/replies/<file>`; a reply whose
 * file is missing fails visibly rather than silently (see `sendQuickReply`).
 */
val QUICK_REPLIES: List<QuickReply> = listOf(
    QuickReply("leave", "Please leave it at the door.", "leave-at-door.wav"),
    QuickReply("coming", "We'll be right there.", "be-right-there.wav"),
    QuickReply("cant", "Nobody can come to the door right now.", "cant-come.wav"),
)

/**
 * Build the go2rtc call that pushes [reply] out of [cameraName]'s speaker.
 *
 * go2rtc's `dst=` form sends a source INTO a stream's audio backchannel, which is how a camera's
 * speaker is reached. That is the whole reason this feature needs no audio code in the app: the
 * obvious approach — swapping a file in for the microphone inside `TalkButton`'s WebRTC session —
 * would mean writing a custom `AudioDeviceModule`, and would only ever work on Android.
 *
 * **PCMU** because that is what the cameras advertise as their sendonly backchannel codec (their
 * SDP carries `a=rtpmap:0 PCMU/8000` alongside the recvonly AAC). Asking for anything else makes
 * go2rtc transcode at best and fail at worst.
 *
 * Everything is URL-encoded because the source contains `:`, `/` and a `#` fragment that would
 * otherwise be read as the end of the query.
 */
fun quickReplyPath(cameraName: String, reply: QuickReply): String {
    val src = "ffmpeg:/config/replies/${reply.file}#audio=pcmu"
    return "go2rtc/api/streams?dst=${encode(cameraName)}&src=${encode(src)}"
}

/**
 * Percent-encode for a query value. Hand-rolled rather than `URLEncoder` so this stays a pure
 * JVM-testable function with no android.net or java.net dependency, and so `+` never appears
 * where go2rtc expects `%20`.
 */
private fun encode(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() || c in "-_.~") append(c) else append('%').append("%02X".format(b))
    }
}

/**
 * Which cameras may be offered a reply.
 *
 * Gated on go2rtc serving the camera, because go2rtc is what carries the backchannel — a camera it
 * does not serve has no path to a speaker at all. Reusing the same signal as the live tier
 * (`Go2rtcStreams`) rather than inventing a second notion of "can this camera talk", so the two
 * can never disagree.
 *
 * Fails CLOSED: unknown means no button. An action that appears and then does nothing is worse
 * than one that never appeared, which is the same reason Reply is absent rather than disabled on
 * cameras without a speaker.
 */
fun canPlayReplies(go2rtcServesCamera: Boolean?): Boolean = go2rtcServesCamera == true
