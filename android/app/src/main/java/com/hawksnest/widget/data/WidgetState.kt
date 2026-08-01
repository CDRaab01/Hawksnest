package com.hawksnest.widget.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hawksnest.core.logic.LedColor
import com.hawksnest.core.logic.SCENE_PAD_KEYS
import com.hawksnest.core.logic.ScenePadKey
import com.hawksnest.core.logic.WIDGET_TEMP_COLD_BELOW_DEFAULT
import com.hawksnest.core.logic.ZEN32_DEFAULT_LEDS
import com.hawksnest.core.logic.WIDGET_TEMP_HOT_ABOVE_DEFAULT
import com.hawksnest.core.logic.WIDGET_TEMP_WARM_ABOVE_DEFAULT
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

    /**
     * The area the entity is in, resolved from HA's registries when the widget was configured.
     *
     * Stored rather than looked up because the widget process only speaks REST and HA exposes the
     * area registry over the WebSocket only — see `temperatureTitle`. Absent for widgets placed
     * before this existed, and for entities HA has not assigned to an area; both fall back to the
     * entity name rather than showing a blank title.
     */
    val ROOM = stringPreferencesKey("room")

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

    /**
     * The temperature widget's three colour thresholds, in the SENSOR'S OWN UNIT.
     *
     * Per widget instance, not global: a nursery and a garage want different
     * answers to "is this OK?", and Glance already files these per `GlanceId`.
     * Stored as doubles so a °C household can use 20.5 without a second format.
     */
    val TEMP_COLD_BELOW = doublePreferencesKey("temp_cold_below")
    val TEMP_WARM_ABOVE = doublePreferencesKey("temp_warm_above")
    val TEMP_HOT_ABOVE = doublePreferencesKey("temp_hot_above")

    /**
     * The scene pad's second entity: the wall controller's own relay.
     *
     * Write-only — commands go to it, nothing is ever read back, and it has no [STATE] of its own
     * here. Naming it "companion" rather than "relay" because the slot is the general one: a
     * widget's primary entity is the one it *draws*, and this is the one it can also *drive*.
     */
    val COMPANION_ENTITY_ID = stringPreferencesKey("companion_entity_id")

    /**
     * The scene pad's per-key configuration, keyed by [ScenePadKey] name.
     *
     * Stored as individual keys rather than one serialised blob so that a widget placed before a
     * field existed simply falls back on that field, exactly as the temperature thresholds do —
     * rather than failing to parse and losing the lot.
     */
    fun scenePreset(key: ScenePadKey) = stringPreferencesKey("scene_preset_${key.name}")
    fun sceneLed(key: ScenePadKey) = stringPreferencesKey("scene_led_${key.name}")
}

/**
 * Which of a widget's entities a command is aimed at.
 *
 * Almost every widget has one entity and this is never mentioned. The scene pad has two — the
 * preset selector it draws and the relay it also drives — and the difference matters far more than
 * "which id to use": a command to the companion must skip the optimistic draw, the pending marker
 * and the confirming read, all of which are about the entity on screen. See
 * `WidgetRepository.actOnCompanion`.
 */
enum class ActionTarget { PRIMARY, COMPANION }

/** The scene pad's per-instance configuration, as the config screen collects it. */
data class ScenePadConfig(
    val relayEntityId: String?,
    val presets: Map<ScenePadKey, String>,
    val leds: Map<ScenePadKey, LedColor>,
)

internal fun Preferences.companionEntityId(): String? =
    this[WidgetKeys.COMPANION_ENTITY_ID]?.takeIf { it.isNotBlank() }

/** The preset each small key selects. Absent keys mean the owner left that slot empty. */
internal fun Preferences.scenePresets(): Map<ScenePadKey, String> =
    SCENE_PAD_KEYS.mapNotNull { key ->
        this[WidgetKeys.scenePreset(key)]?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()

/**
 * Each key's LED colour, falling back to the measured ZEN32 default per key rather than as a set —
 * so a widget stored before a key gained a colour keeps the four it has.
 */
internal fun Preferences.sceneLeds(): Map<ScenePadKey, LedColor> =
    ScenePadKey.entries.associateWith { key ->
        this[WidgetKeys.sceneLed(key)]
            ?.let { name -> LedColor.entries.firstOrNull { it.name == name } }
            ?: ZEN32_DEFAULT_LEDS.getValue(key)
    }

internal fun MutablePreferences.putScenePad(config: ScenePadConfig) {
    config.relayEntityId?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { this[WidgetKeys.COMPANION_ENTITY_ID] = it }
    config.presets.forEach { (key, preset) -> this[WidgetKeys.scenePreset(key)] = preset }
    config.leds.forEach { (key, led) -> this[WidgetKeys.sceneLed(key)] = led.name }
}

/** Thresholds for this widget, each falling back to its default independently — so a
 *  widget placed before the "hot" band existed keeps its own cold/warm numbers and
 *  simply gains a sensible hot one, rather than being reset to all three defaults. */
internal fun Preferences.tempThresholds(): Triple<Double, Double, Double> = Triple(
    this[WidgetKeys.TEMP_COLD_BELOW] ?: WIDGET_TEMP_COLD_BELOW_DEFAULT,
    this[WidgetKeys.TEMP_WARM_ABOVE] ?: WIDGET_TEMP_WARM_ABOVE_DEFAULT,
    this[WidgetKeys.TEMP_HOT_ABOVE] ?: WIDGET_TEMP_HOT_ABOVE_DEFAULT,
)

internal fun Preferences.entityId(): String? = this[WidgetKeys.ENTITY_ID]

/** The configured room, or null when HA had no area for the entity (or the widget predates it). */
internal fun Preferences.room(): String? = this[WidgetKeys.ROOM]?.takeIf { it.isNotBlank() }

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
