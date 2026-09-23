package com.hawksnest.core.logic

/**
 * Camera-picture aspect handling.
 *
 * Hawksnest historically forced every camera frame into a 16:9 box. That is wrong for two shapes
 * in the fleet: the Reolink dual-lens outdoor cameras stitch two lenses into one ~32:9 panorama
 * (`1536x432`), and the Reolink doorbell is 4:3. A 16:9 box letterboxes the panorama into a thin
 * strip (the player, whose renderers already scale-to-fit) or crops its two lenses off entirely
 * (anywhere using `ContentScale.Crop` — the snapshot tiers and the home grid tile).
 *
 * These helpers let a surface render at the media's TRUE aspect ratio instead — matching how
 * Reolink itself presents a Duo: one wide picture you pinch-zoom into (`ZoomableFrame` already
 * provides the zoom). The ratio is measured from the media once it loads, so **no per-camera
 * resolution table is needed** and a 4:3 doorbell is corrected by the same code path.
 *
 * 1:1 port of `src/lib/mediaAspect.ts` — keep the constants identical so the platforms cannot
 * drift (ARCHITECTURE.md's platform-parity rule). `useMediaAspect` itself does not port: it is
 * React state plumbing, and its Compose counterpart is `CameraSession.videoSize`, which the
 * renderers already feed.
 */

/** Fallback ratio (width/height) until the media reports real dimensions. */
const val DEFAULT_ASPECT = 16f / 9f

/**
 * A stitched dual-lens panorama is ~3.56:1; a normal camera is 1.33–1.78:1. 2.2 sits clearly
 * between the two families, so anything at least this wide is treated as a panorama that should
 * span its row rather than sit cropped in a grid cell.
 */
const val WIDE_ASPECT_THRESHOLD = 2.2f

/**
 * width / height, or null when either dimension is unknown — a renderer reports 0×0 before the
 * first frame and a decoder before `load`, and 0 must never become an aspect ratio.
 */
fun aspectFromDimensions(width: Int, height: Int): Float? =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null

/** Whether a ratio is that of a dual-lens panorama (see [WIDE_ASPECT_THRESHOLD]). */
fun isWideAspect(ratio: Float): Boolean = ratio >= WIDE_ASPECT_THRESHOLD

/**
 * Pack cameras into wall rows: a **wide** (panorama) camera takes a whole row, the rest pair up.
 *
 * No web twin by design — there the CSS grid does this for free (`col-span-*` in `CameraWall.tsx`).
 * The shared part is the [isWideAspect] threshold; only the packing is platform-shaped, because
 * Android's wall is a `Column` of fixed 2-up `Row`s with no `GridItemSpan` to reach for. Leaves a
 * short row where a wide tile displaces its partner, exactly as CSS grid auto-placement does.
 */
fun <T> wallRows(items: List<T>, columns: Int = 2, isWide: (T) -> Boolean): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    var run = mutableListOf<T>()
    for (item in items) {
        if (isWide(item)) {
            if (run.isNotEmpty()) { rows.add(run); run = mutableListOf() }
            rows.add(listOf(item))
        } else {
            run.add(item)
            if (run.size == columns) { rows.add(run); run = mutableListOf() }
        }
    }
    if (run.isNotEmpty()) rows.add(run)
    return rows
}
