package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity

/**
 * What one observation of the ring-mqtt event selector means while waiting for a selected clip.
 *
 * Extracted from `CameraPlayerViewModel.awaitRecordingUrl`, where it lived as a `String?` with two
 * sentinel meanings — `null` for terminal failure and `""` for keep-waiting — which made the one
 * genuinely subtle rule in the recorded-clip path (below) untestable and easy to misread.
 */
sealed interface RingClipReading {
    /** Nothing conclusive yet: wrong option showing, no URL, or the URL still belongs to the previous clip. */
    data object Wait : RingClipReading

    /** ring-mqtt says this selection has nothing to play. Terminal — do not keep waiting. */
    data object Missing : RingClipReading

    /** A playable URL that belongs to the requested option. */
    data class Ready(val url: String) : RingClipReading
}

/**
 * Decide what a selector observation means for the clip we asked for.
 *
 * The load-bearing rule is the last one: a URL is only accepted if it **changed**, unless the
 * option was already selected before we asked. ring-mqtt publishes state and attributes together,
 * but HA delivers them as two separate updates, so there is a window where the selector already
 * reads the new option while `recordingUrl` still holds the previous clip's URL. Accepting that
 * window plays the wrong moment — silently, and convincingly, because a real video appears.
 *
 * When the option was already active, there is nothing to wait for: re-selecting it will not
 * change the URL, so the published one is correct as-is. That case also covers two selector
 * options mapping to a single Ring event.
 *
 * Note what this deliberately does *not* do: fall back to whatever URL happens to be published
 * when nothing new arrives. ring-mqtt leaves the old URL in place when its event lookup finds
 * nothing — common on the 24/7 cameras, whose footage is continuous rather than per-event — so
 * "take what's there" is exactly how you end up playing a different moment with no error shown.
 * Timing out and failing is honest; the caller's Retry recovers the legitimate case.
 */
fun ringClipReading(
    selector: HassEntity?,
    option: String,
    alreadySelected: Boolean,
    urlBefore: String?,
): RingClipReading {
    if (selector?.state != option) return RingClipReading.Wait
    if (ringRecordingMissing(selector)) return RingClipReading.Missing
    val url = ringRecordingUrl(selector) ?: return RingClipReading.Wait
    return if (alreadySelected || url != urlBefore) {
        RingClipReading.Ready(url)
    } else {
        RingClipReading.Wait
    }
}
