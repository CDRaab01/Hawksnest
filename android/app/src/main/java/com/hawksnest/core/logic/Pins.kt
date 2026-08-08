package com.hawksnest.core.logic

/**
 * Pin-list semantics for the Devices tab's pinned rail, mirroring the web's
 * `src/store/prefsStore.ts` exactly: `stored == null` means "never customized —
 * use the static seed"; the first edit materializes the seed into a concrete
 * list so later reads honor the user's choices. Pure so the ViewModel stays a
 * thin adapter and these rules are unit-tested.
 */
fun effectivePins(stored: List<String>?, seed: List<String>): List<String> = stored ?: seed

/** Add [id] if absent (at the end, like web `togglePin`), remove it if present. */
fun togglePin(current: List<String>, id: String): List<String> =
    if (id in current) current - id else current + id

/** Move [id] by [delta] positions, clamped at the ends; unknown ids are a no-op. */
fun movePin(current: List<String>, id: String, delta: Int): List<String> {
    val from = current.indexOf(id)
    if (from < 0) return current
    val to = (from + delta).coerceIn(0, current.lastIndex)
    if (from == to) return current
    return current.toMutableList().apply {
        removeAt(from)
        add(to, id)
    }
}
