package com.hawksnest.core.logic

/**
 * Which camera-movement controls a camera actually has, resolved from the HA
 * entities the **official Reolink integration** creates.
 *
 * Hawksnest reaches the cameras through Home Assistant, never directly: the
 * integration owns the camera's HTTP API session and serialises commands, so the
 * app only presses buttons and sets numbers (`servers enforce, clients present`).
 *
 * ## The entity ids are NOT derived from the camera base
 *
 * The obvious implementation — `button.${cameraBase}_ptz_up` — is wrong on this
 * deployment and fails silently. Measured 2026-07-30: the stairway camera's
 * Frigate entity is `camera.first_floor_stairway`, but its Reolink *device* is
 * named `stairway`, so its PTZ entities are `button.stairway_ptz_*`. Deriving ids
 * from the base would drop PTZ on that camera with no error — the chrome would
 * simply never appear, reading as "this camera can't move" rather than as a bug.
 *
 * So the slug is *discovered* from the entities that exist and matched to the
 * camera. An exact match always wins; otherwise one slug must be the other's
 * **trailing whole segments** (`stairway` ~ `first_floor_stairway`; `big` does not
 * match `big_room`). An ambiguous match yields null rather than a guess, because
 * pointing the pad at the wrong camera moves the wrong lens, and [aliases] pins a
 * name explicitly when a heuristic shouldn't be trusted.
 *
 * 1:1 port of `src/lib/cameraPtz.ts` — keep the two in lockstep.
 */
data class PtzControls(
    /** The Reolink device slug these entities are named after — often, but not always, the camera base. */
    val slug: String,
    val up: String,
    val down: String,
    val left: String,
    val right: String,
    /** Halts a move in progress. Always present when the directions are. */
    val stop: String,
    /** Optical zoom (`number`), or null on models without it (the E1 Pro). */
    val zoom: String? = null,
    /** Manual focus position (`number`), or null. Meaningful only with autofocus off. */
    val focus: String? = null,
    /** Autofocus toggle (`switch`), or null. */
    val autofocus: String? = null,
    /**
     * Saved-position select, or null.
     *
     * Null is the normal state here, not a defect: the integration creates this
     * only once a preset is actually saved **on the camera**. Save one in the
     * Reolink app and it appears.
     */
    val preset: String? = null,
)

/** Is one slug the other's trailing whole segments? (`stairway` ~ `first_floor_stairway`) */
internal fun slugsMatch(a: String, b: String): Boolean =
    a == b || a.endsWith("_$b") || b.endsWith("_$a")

/**
 * Pick the integration's device slug for [cameraBase] from the slugs that actually exist.
 *
 * Shared with `DoorbellControls.kt` so the two resolvers can never disagree about which device a
 * camera is. An explicit alias wins, then an exact match (never let a fuzzy match override an
 * exact one), then exactly one trailing-segment match. Ambiguity yields null rather than a guess —
 * pointing a control at the wrong camera is worse than showing no control.
 */
internal fun pickDeviceSlug(
    cameraBase: String,
    candidates: List<String>,
    aliases: Map<String, String> = emptyMap(),
): String? {
    val alias = aliases[cameraBase]
    return when {
        alias != null && alias in candidates -> alias
        cameraBase in candidates -> cameraBase
        else -> candidates.filter { slugsMatch(it, cameraBase) }.singleOrNull()
    }
}

private val PTZ_UP = Regex("""^button\.(.+)_ptz_up$""")

/**
 * The movement controls for [cameraBase] (the part after `camera.`), or null when
 * the camera has none.
 *
 * @param entityIds every entity id currently known — candidate slugs are discovered
 *   from it, so a camera whose integration is absent resolves to null.
 * @param aliases optional explicit `cameraBase -> reolink slug` overrides.
 */
fun resolvePtz(
    cameraBase: String,
    entityIds: Collection<String>,
    aliases: Map<String, String> = emptyMap(),
): PtzControls? {
    val ids = entityIds as? Set<String> ?: entityIds.toSet()

    val candidates = ids.mapNotNull { PTZ_UP.find(it)?.groupValues?.get(1) }
    if (candidates.isEmpty()) return null

    val slug = pickDeviceSlug(cameraBase, candidates, aliases) ?: return null

    fun has(id: String): String? = if (id in ids) id else null

    val down = has("button.${slug}_ptz_down")
    val left = has("button.${slug}_ptz_left")
    val right = has("button.${slug}_ptz_right")
    val stop = has("button.${slug}_ptz_stop")
    // Fail closed on a half-present set: a pad that can move but not stop is worse
    // than no pad, because a press would leave the camera panning.
    if (down == null || left == null || right == null || stop == null) return null

    return PtzControls(
        slug = slug,
        up = "button.${slug}_ptz_up",
        down = down,
        left = left,
        right = right,
        stop = stop,
        zoom = has("number.${slug}_zoom"),
        focus = has("number.${slug}_focus"),
        autofocus = has("switch.${slug}_auto_focus"),
        preset = has("select.${slug}_ptz_preset"),
    )
}
