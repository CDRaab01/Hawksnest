package com.hawksnest.core.logic

/**
 * How a camera opens when something outside the player asks for it: a notification tap or one of
 * its buttons. The player starts in this mode for the camera that was asked for only; switching
 * cameras inside it always lands live.
 */
enum class CameraStart {
    /** Live video, nothing else. The default for every tile tap. */
    LIVE,

    /** Live, with Talk already opening the mic (the doorbell notification's "Talk"). */
    TALK,

    /** Live, with the quick-reply sheet already up (the fallback when a reply from the shade
     *  could not be played). */
    REPLY,
}
