package com.hawksnest.core.logic

/**
 * What a camera tile on Home can truthfully say about the picture it is showing.
 *
 * The header used to read "14/14 live" over a black tile, two sleeping cameras and a picture whose
 * refresh had been failing for minutes, and every wired tile said "2m ago" at once — HA rotating
 * the camera's access token, not the age of any picture. These states are what the tile badge and
 * the header count are built from instead, so the two can never disagree.
 */
enum class TilePicture {
    /** A picture recent enough to call current. */
    LIVE,

    /** A picture is showing, but it is old, or the last attempt to refresh it failed. */
    STALE,

    /** A battery camera parked to save power. Its last picture is not current, by design. */
    ASLEEP,

    /** HA reports the camera unavailable, or no picture has ever loaded and the fetch failed. */
    NO_SIGNAL,

    /** The first picture is still on its way. */
    LOADING,
}

/**
 * How old a tile's picture may be and still count as live. Six minutes covers a Ring battery
 * camera, which republishes its snapshot every 300 s, plus a refresh or two of slack; a wired
 * camera refetches every 10 s, so on those anything this old means the refreshes are failing.
 */
const val TILE_STALE_AFTER_MS = 6 * 60_000L

/**
 * The tile's state, from what HA says about the camera and what the tile's own fetches saw.
 *
 * [pictureAtMs] is when the shown picture was taken, as best the app knows: the time a Frigate
 * snapshot was fetched (Frigate serves its current frame), a Ring snapshot entity's own update time,
 * or when a live frame was grabbed. Null means a picture is showing but its time is unknown.
 */
fun tilePicture(
    haAvailable: Boolean,
    asleep: Boolean,
    hasPicture: Boolean,
    lastFetchFailed: Boolean,
    pictureAtMs: Long?,
    nowMs: Long,
): TilePicture = when {
    asleep -> TilePicture.ASLEEP
    !haAvailable -> TilePicture.NO_SIGNAL
    !hasPicture -> if (lastFetchFailed) TilePicture.NO_SIGNAL else TilePicture.LOADING
    lastFetchFailed -> TilePicture.STALE
    pictureAtMs != null && nowMs - pictureAtMs > TILE_STALE_AFTER_MS -> TilePicture.STALE
    else -> TilePicture.LIVE
}

/**
 * The Cameras header: "12 live", or "9 live · 2 asleep · 1 no signal" when not everything is.
 * Cameras still loading their first picture are left out rather than counted as live; if nothing
 * has loaded yet the header says so.
 */
fun cameraCountLabel(states: List<TilePicture>): String {
    if (states.isEmpty()) return ""
    if (states.all { it == TilePicture.LOADING }) return "Loading"
    val parts = listOf(
        TilePicture.LIVE to "live",
        TilePicture.STALE to "stale",
        TilePicture.ASLEEP to "asleep",
        TilePicture.NO_SIGNAL to "no signal",
    ).mapNotNull { (state, word) ->
        states.count { it == state }.takeIf { it > 0 }?.let { "$it $word" }
    }
    return parts.joinToString(" · ").ifEmpty { "0 live" }
}
