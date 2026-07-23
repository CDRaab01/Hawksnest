package com.hawksnest.widget.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Each widget's persisted state.
 *
 * Glance already gives every widget instance its own Preferences file (keyed by `GlanceId`) and
 * recomposes it when that file changes, so this is the whole persistence layer — no separate
 * DataStore, and updates land on screen without a manual redraw.
 *
 * What is stored is deliberately not "whatever we last saw". [FETCHED_AT] is stored alongside the
 * state precisely so the render path can decide the reading has expired: `securityStateFresh`
 * makes a lock or alarm value unusable after a minute, and a widget that comes back from the dead
 * an hour later says "Checking…" rather than repeating a stale "Locked".
 */
internal object WidgetKeys {
    /** The controlled entity. Absent = never configured (or the configuration was cancelled). */
    val ENTITY_ID = stringPreferencesKey("entity_id")

    /** Resolved display name, cached so the widget has a title before its first fetch lands. */
    val NAME = stringPreferencesKey("name")

    val STATE = stringPreferencesKey("state")
    val ATTRIBUTES = stringPreferencesKey("attributes")

    /** When [STATE] was read from HA. The expiry that keeps stale security states off screen. */
    val FETCHED_AT = longPreferencesKey("fetched_at")

    /** When the in-flight control started, or absent. Expires via `widgetPending`. */
    val PENDING_SINCE = longPreferencesKey("pending_since")

    /** When a "tap again" confirmation was armed, and for which service. */
    val CONFIRM_SINCE = longPreferencesKey("confirm_since")
    val CONFIRM_SERVICE = stringPreferencesKey("confirm_service")

    /** The last [WidgetBlocker], by name, or absent when the last fetch succeeded. */
    val BLOCKER = stringPreferencesKey("blocker")
}

internal fun Preferences.entityId(): String? = this[WidgetKeys.ENTITY_ID]

internal fun Preferences.blocker(): WidgetBlocker? =
    this[WidgetKeys.BLOCKER]?.let { name -> WidgetBlocker.entries.firstOrNull { it.name == name } }

internal fun Preferences.pendingSince(): Long? = this[WidgetKeys.PENDING_SINCE]

internal fun Preferences.confirmSince(): Long? = this[WidgetKeys.CONFIRM_SINCE]

internal fun Preferences.confirmService(): String? = this[WidgetKeys.CONFIRM_SERVICE]

/** The persisted reading, or null when this widget has never completed a fetch. */
internal fun Preferences.snapshot(json: Json): WidgetSnapshot? {
    val entityId = this[WidgetKeys.ENTITY_ID] ?: return null
    val state = this[WidgetKeys.STATE] ?: return null
    val fetchedAt = this[WidgetKeys.FETCHED_AT] ?: return null
    val attributes = this[WidgetKeys.ATTRIBUTES]
        ?.let { runCatching { json.decodeFromString(JsonObject.serializer(), it) }.getOrNull() }
        ?: JsonObject(emptyMap())
    return WidgetSnapshot(
        entityId = entityId,
        name = this[WidgetKeys.NAME] ?: entityId,
        state = state,
        attributes = attributes,
        fetchedAtMs = fetchedAt,
    )
}

internal fun MutablePreferences.putSnapshot(snapshot: WidgetSnapshot, json: Json) {
    this[WidgetKeys.ENTITY_ID] = snapshot.entityId
    this[WidgetKeys.NAME] = snapshot.name
    this[WidgetKeys.STATE] = snapshot.state
    this[WidgetKeys.ATTRIBUTES] = json.encodeToString(JsonObject.serializer(), snapshot.attributes)
    this[WidgetKeys.FETCHED_AT] = snapshot.fetchedAtMs
    remove(WidgetKeys.BLOCKER)
}

internal fun MutablePreferences.putBlocker(blocker: WidgetBlocker) {
    this[WidgetKeys.BLOCKER] = blocker.name
}

/**
 * Drop the stored reading so the widget renders as unknown.
 *
 * The app-side counterpart is `maskSecurityStates`, which collapses lock and alarm entities the
 * moment the socket drops. Same rule, applied at the moment a widget's fetch fails: a lock we
 * can no longer reach must not keep displaying the last thing it said.
 */
internal fun MutablePreferences.maskState() {
    remove(WidgetKeys.STATE)
    remove(WidgetKeys.ATTRIBUTES)
    remove(WidgetKeys.FETCHED_AT)
}

internal fun MutablePreferences.clearPending() {
    remove(WidgetKeys.PENDING_SINCE)
}

internal fun MutablePreferences.clearConfirm() {
    remove(WidgetKeys.CONFIRM_SINCE)
    remove(WidgetKeys.CONFIRM_SERVICE)
}
