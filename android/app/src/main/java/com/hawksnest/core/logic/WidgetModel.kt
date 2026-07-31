package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.stringAttr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/*
 * Pure view-models for the home-screen AppWidgets (`widget/`). Kept here, Compose-free, for the
 * same reason [alarmView] and [lockVaultView] are: the widget layer is the hardest surface to test
 * (RemoteViews, launcher-hosted, cold process), so every rule that matters — what may be rendered
 * from cache, when a pending spinner expires, when a confirm tap lapses — lives in plain Kotlin
 * with unit tests, and the Glance code only draws.
 *
 * The security invariant the app enforces with `maskSecurityStates` (never show a lock/alarm state
 * you can't vouch for) has a sharper edge out here. In-app, a stale state is impossible: the
 * WebSocket either streams or the connection is visibly down. A widget has neither — it is a
 * picture drawn at an unknown time by a process that may since have died. So lock and alarm
 * states carry an expiry ([WIDGET_SECURITY_FRESH_MS]); past it they render as *unknown*, never as
 * the last thing we saw. Lights have no such rule — a stale lamp is a cosmetic wrong, not a
 * security one — so they render from cache with their age shown once it stops being plausible.
 *
 * The expiry alone isn't enough, though, and the reason is worth stating: a drawn widget is
 * pixels. Nothing redraws it because a reading aged out, so a frame that said "Locked" when it was
 * true can still be on the home screen an hour later. Rather than schedule redraws forever (a
 * permanent background poll, for a widget only reachable on the tailnet anyway), the security
 * views carry [LockWidgetView.readAtMs] / [AlarmWidgetView.readAtMs] and the widget prints the
 * clock time beside the state. A stale frame then reads "Locked · 10:42" and tells the truth
 * about itself no matter when it is looked at.
 */

/** Which control a home-screen widget hosts. */
enum class WidgetKind { LIGHT, LOCK, ALARM, TEMPERATURE }

// --- temperature widget ------------------------------------------------------

/**
 * Which side of the comfortable range a reading falls on.
 *
 * Four bands, not a gradient: the widget answers one question from across a room —
 * "is it OK in there?" — and hard flips read at a glance where a blend does not.
 *
 * WARM and HOT are deliberately separate rather than one "too warm". They mean
 * different things in a nursery: warm is a nudge to open a window, hot is a
 * problem to deal with now. One orange band would have flattened that into a
 * single "not ideal" that stops being urgent once you're used to seeing it.
 */
enum class TempBand { COLD, GOOD, WARM, HOT }

/**
 * Default thresholds, in the sensor's own unit, chosen for the nursery this was
 * built for (typical guidance is roughly 68-72°F, and 77°F is where it stops being
 * a comfort question). They are only a starting point: every widget instance
 * carries its own set, chosen when it is placed.
 *
 * NOTE these are Fahrenheit because that is what this household's sensors report.
 * The widget never converts — it renders whatever HA gives it, so a °C sensor with
 * °C thresholds works identically without a unit flag anywhere in the code.
 */
const val WIDGET_TEMP_COLD_BELOW_DEFAULT = 68.0
const val WIDGET_TEMP_WARM_ABOVE_DEFAULT = 72.0
const val WIDGET_TEMP_HOT_ABOVE_DEFAULT = 77.0

/**
 * Which band [value] falls in, given the three thresholds.
 *
 * Boundaries belong to the *calmer* band: exactly [coldBelow] is GOOD, exactly
 * [warmAbove] is GOOD, exactly [hotAbove] is WARM. A threshold of "77" reads to a
 * person as "77 is warm, 77.1 is hot", and a sensor parked precisely on the number
 * must not flicker between two colours.
 *
 * The three are sorted rather than trusted, so a set entered out of order still
 * describes a usable ladder instead of a widget stuck in one colour. That matters
 * more here than with two: three fields are three chances to fat-finger.
 */
fun temperatureBand(
    value: Double,
    coldBelow: Double,
    warmAbove: Double,
    hotAbove: Double,
): TempBand {
    val (low, mid, high) = listOf(coldBelow, warmAbove, hotAbove).sorted()
    return when {
        value < low -> TempBand.COLD
        value <= mid -> TempBand.GOOD
        value <= high -> TempBand.WARM
        else -> TempBand.HOT
    }
}

/**
 * The PULSE channel a band wears — for the panel rim and, for everything but HOT,
 * the reading itself. Reuses the existing palette so the widget matches the rest of
 * the home screen and picks up theme changes for free.
 *
 * RECOVERY (green) for good and STREAK (orange) for warm are the obvious reads;
 * COLD takes EFFORT (blue), the only cool channel there is. HOT has **no channel**:
 * PULSE's four channels are blue/violet/orange/green and none of them is red, so
 * hot borrows the app's *error* colour instead — which is the honest semantic
 * anyway. See `temperatureIsAlert`.
 */
fun temperatureChannel(band: TempBand): Channel? = when (band) {
    TempBand.COLD -> Channel.EFFORT
    TempBand.GOOD -> Channel.RECOVERY
    TempBand.WARM -> Channel.STREAK
    TempBand.HOT -> null
}

/** True for the band that renders in the error red rather than a channel colour. */
fun temperatureIsAlert(band: TempBand): Boolean = band == TempBand.HOT

/** Short label under the reading, so the colour is never the only signal (colour
 *  blindness, and a glance in bright sun). */
fun temperatureLabel(band: TempBand): String = when (band) {
    TempBand.COLD -> "Cold"
    TempBand.GOOD -> "Comfortable"
    TempBand.WARM -> "Warm"
    TempBand.HOT -> "Hot"
}

/**
 * The reading as displayed: one decimal at most, and never a bare ".0".
 *
 * HA sends temperatures as strings that may be "74.8", "74", or "unknown"; a
 * non-numeric state returns null so the widget can say so rather than printing
 * garbage.
 */
fun temperatureDisplay(state: String?): String? {
    val v = state?.trim()?.toDoubleOrNull() ?: return null
    val rounded = Math.round(v * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * How much room a widget has been given, and therefore how much it says.
 *
 * Two tiers, not three: the full layout wants a chipped two-line header plus a 52dp control row,
 * which needs about 118dp, and a one-row widget has nowhere near that. Compact keeps every control
 * and collapses the header to a single small line with an inline glyph.
 */
enum class WidgetSizeTier { COMPACT, FULL }

/** Below this a widget cannot fit the chipped two-line header over a full-height control row. */
const val WIDGET_FULL_MIN_HEIGHT_DP = 120

/**
 * The compact tier's `SizeMode.Responsive` bucket height. Any value below
 * [WIDGET_FULL_MIN_HEIGHT_DP] lands in [sizeTier]'s COMPACT; this one is roughly a launcher row
 * less its gutters, so the framework can still pick the bucket on a squeezed placement (it only
 * picks a layout whose bucket fits inside the real size).
 */
const val WIDGET_COMPACT_BUCKET_DP = 56

/**
 * The compact tier's *taller* bucket — a two-row placement on a coarse launcher grid, and still
 * well short of [WIDGET_FULL_MIN_HEIGHT_DP]. A widget this tall has room for a second header line
 * even though it has nowhere near enough for the full tier's chip and 52dp control row, and that
 * second line is the only way a narrow widget can name itself (see [compactNamePlacement]).
 *
 * Sized from what the stacked layout actually costs, not from the grid: 12dp of panel padding, two
 * 12sp lines at ~16dp each, and the 4dp spacer come to 48dp, so 80dp leaves a ~32dp full-width
 * button at the very bottom of the range and a comfortable one immediately above it. It was 90dp
 * first, which is a real two-row widget on most launchers but *just* missed a measured 86dp
 * placement — and missing means the name vanishes entirely, so this errs low on purpose.
 */
const val WIDGET_COMPACT_TALL_BUCKET_DP = 80

fun sizeTier(heightDp: Int): WidgetSizeTier =
    if (heightDp < WIDGET_FULL_MIN_HEIGHT_DP) WidgetSizeTier.COMPACT else WidgetSizeTier.FULL

/**
 * Below this width a compact header cannot fit the device name *beside* its state and read time.
 * Sized for the worst case that has to survive: an inline glyph, the panel's padding, and
 * "Unlocked · 10:42 PM" at 12sp come to about 145dp on their own, so 200dp — about a three-cell
 * placement — is the point where a short name still has somewhere to go.
 */
const val WIDGET_NAME_MIN_WIDTH_DP = 200

/** Where a compact header puts the device name, given the room it actually has. */
enum class CompactName {
    /** Nowhere for it: the one line is the state and the time it was read, nothing else. */
    HIDDEN,

    /** Beside the state, sharing the single line. */
    INLINE,

    /** On its own line above the state — too narrow to share, tall enough not to have to. */
    STACKED,
}

/**
 * How a compact header spends its room on the device's name.
 *
 * The state *and* the time it was read always come first on a lock or the alarm, because those are
 * what stop a frame left on the home screen from quietly lying (see [LockWidgetView.readAtMs]).
 * The name is the part that can be inferred from where the widget sits, so it is what yields.
 *
 * But "yields" is not "never appears", which is what the first two passes at this got wrong. It
 * was a kind question, then a width question; it is really a question of *room*, and room has two
 * dimensions. A lock squeezed small is usually short rather than narrow — the button below it is
 * enormous — so when the width won't take a name beside the state, the height very often will take
 * it above. Only a widget that is both narrow and a single row genuinely has nowhere to put it, and
 * there [HIDDEN] still holds the line.
 */
fun compactNamePlacement(kind: WidgetKind, widthDp: Int, heightDp: Int): CompactName = when {
    // A light's state line is short ("On · 60%") and carries no timestamp, so its name has always
    // shared the line at any width — and a lamp shown wrong is a cosmetic error, not a security one.
    kind == WidgetKind.LIGHT -> CompactName.INLINE
    widthDp >= WIDGET_NAME_MIN_WIDTH_DP -> CompactName.INLINE
    heightDp >= WIDGET_COMPACT_TALL_BUCKET_DP -> CompactName.STACKED
    else -> CompactName.HIDDEN
}

/**
 * Which HA domains a widget of this kind can control.
 *
 * The light widget is `light` only. It briefly took `switch` too, on the theory that relay-style
 * lights land there — but on this house `switch.*` is overwhelmingly ring-mqtt camera plumbing
 * (live/event streams, motion-detection toggles, sirens), which buried the dozen real lights under
 * dozens of things nobody would ever put on a home screen. The app itself keeps the two domains
 * apart (`Cards.kt` maps them to different cards); conflating them here was the deviation.
 */
fun widgetCandidateDomains(kind: WidgetKind): Set<String> = when (kind) {
    WidgetKind.LIGHT -> setOf("light")
    WidgetKind.LOCK -> setOf("lock")
    WidgetKind.ALARM -> setOf("alarm_control_panel")
    WidgetKind.TEMPERATURE -> setOf("sensor")
}

/**
 * The entities worth offering for a widget of this kind: the right domain, reachable, not
 * housekeeping, and not a duplicate of something already in the list.
 *
 * [categories] and [platforms] come from HA's entity registry, which is WebSocket-only. The
 * configuration screen runs inside the app, so it can hand them over from the live connection;
 * when it can't — no socket yet, off the tailnet — both default to empty and the filters degrade
 * on their own. [isPrimaryEntity] with no categories falls back to the suffix denylist, and
 * [dedupeRingMqtt] with no platforms returns the list untouched. So this is one code path that
 * gets better when the registry is there rather than two paths to keep in step.
 */
fun widgetCandidates(
    kind: WidgetKind,
    entities: List<HassEntity>,
    categories: Map<String, String> = emptyMap(),
    platforms: Map<String, String> = emptyMap(),
): List<HassEntity> {
    val domains = widgetCandidateDomains(kind)
    val inScope = entities.filter { entity ->
        entity.entityId.substringBefore('.') in domains &&
            // Nothing worth pinning to a home screen; it would render "Unavailable" forever.
            entity.state != "unavailable" &&
            isPrimaryEntity(entity.entityId, categories) &&
            // `sensor` is a huge domain — narrow it to actual thermometers, or the
            // picker offers every battery level and fps counter in the house. The
            // other kinds own their domain outright and need no extra filter.
            (kind != WidgetKind.TEMPERATURE || entity.stringAttr("device_class") == "temperature")
    }
    // The household runs both the Ring integration and ring-mqtt, so a Ring light can appear
    // twice under one name. Same collapse the Devices list does.
    return dedupeRingMqtt(inScope.associateBy { it.entityId }, platforms).values.toList()
}

/**
 * How long a lock/alarm reading stays trustworthy. Past this the widget says "Checking…" and
 * refetches rather than repeating itself. Deliberately short: the cost of being wrong here is
 * "the door reads Locked when it isn't".
 */
const val WIDGET_SECURITY_FRESH_MS = 60_000L

/** Past this, a light's cached reading is shown with its age attached rather than bare. */
const val WIDGET_STALE_AFTER_MS = 15 * 60_000L

/**
 * How long a widget shows a pending spinner before giving up on it. Mirrors
 * `ControlGate.ECHO_TIMEOUT_MS`; also the expiry that stops a spinner surviving forever when the
 * process is killed mid-poll (the pending marker is persisted, so nothing else would clear it).
 */
const val WIDGET_ECHO_TIMEOUT_MS = 30_000L

/** How long an armed "tap again" confirmation stays live before lapsing back to the resting state. */
const val WIDGET_CONFIRM_WINDOW_MS = 5_000L

/**
 * The levels the widget's dim buttons step between.
 *
 * Not a fixed percentage, because a fixed percentage is wrong at both ends: the eye responds to
 * brightness roughly logarithmically, so going 80% → 60% is barely visible while 20% → 1% is the
 * difference between a lit room and a nightlight. Even steps therefore feel coarse where it
 * matters and pointlessly fine where it doesn't. These stops are tight at the bottom and wide at
 * the top, which is how a good physical dimmer is geared — and it means the useful range takes
 * about the same number of taps as before while landing on levels you can actually tell apart.
 *
 * The floor is 1, not 0: turning the light off is the toggle's job, and `brightness_pct: 0` is
 * not portable across HA integrations (see [dimCommit]).
 */
val WIDGET_DIM_STOPS = listOf(1, 5, 10, 20, 30, 40, 50, 65, 80, 100)

/** An entity reading a widget has persisted, with the moment it was fetched. */
data class WidgetSnapshot(
    val entityId: String,
    val name: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val fetchedAtMs: Long,
)

fun HassEntity.toSnapshot(name: String, fetchedAtMs: Long): WidgetSnapshot =
    WidgetSnapshot(entityId, name, state, attributes, fetchedAtMs)

/** Why a widget is showing a message instead of a control. */
enum class WidgetBlocker {
    /** No entity chosen yet (configuration was cancelled, or the entity was deleted from HA). */
    NOT_CONFIGURED,

    /** No HA credentials saved — the app itself is signed out. */
    SIGNED_OUT,

    /** Couldn't reach HA at all. On this setup that nearly always means the tailnet is down. */
    UNREACHABLE,

    /** HA answered, but rejected the token. */
    UNAUTHORIZED,

    /** HA answered, but has no such entity. */
    ENTITY_MISSING,

    /** The command was accepted but nothing settled within [WIDGET_ECHO_TIMEOUT_MS]. */
    NO_RESPONSE,
}

data class WidgetBlockerCopy(val headline: String, val detail: String)

fun blockerCopy(blocker: WidgetBlocker): WidgetBlockerCopy = when (blocker) {
    WidgetBlocker.NOT_CONFIGURED ->
        WidgetBlockerCopy("Not set up", "Tap to choose a device")
    WidgetBlocker.SIGNED_OUT ->
        WidgetBlockerCopy("Signed out", "Tap to connect Hawksnest")
    WidgetBlocker.UNREACHABLE ->
        WidgetBlockerCopy("Can't reach Hawksnest", "Check Tailscale · tap to retry")
    WidgetBlocker.UNAUTHORIZED ->
        WidgetBlockerCopy("Token rejected", "Tap to re-enter it")
    WidgetBlocker.ENTITY_MISSING ->
        WidgetBlockerCopy("Device is gone", "Tap to pick another")
    WidgetBlocker.NO_RESPONSE ->
        WidgetBlockerCopy("Didn't respond", "Tap to try again")
}

/**
 * Is a control still in flight? A persisted pending marker outlives the process that set it, so a
 * widget rendered after a mid-poll kill would spin forever without this expiry. A marker from the
 * future (clock moved backwards) is treated as expired rather than trusted.
 */
fun widgetPending(pendingSinceMs: Long?, nowMs: Long): Boolean =
    pendingSinceMs != null && (nowMs - pendingSinceMs) in 0 until WIDGET_ECHO_TIMEOUT_MS

/** Is a "tap again to confirm" still armed? Same expiry reasoning as [widgetPending]. */
fun widgetConfirmArmed(confirmSinceMs: Long?, nowMs: Long): Boolean =
    confirmSinceMs != null && (nowMs - confirmSinceMs) in 0 until WIDGET_CONFIRM_WINDOW_MS

/** May a lock/alarm reading of this age still be shown as fact? See [WIDGET_SECURITY_FRESH_MS]. */
fun securityStateFresh(fetchedAtMs: Long?, nowMs: Long): Boolean =
    fetchedAtMs != null && (nowMs - fetchedAtMs) in 0 until WIDGET_SECURITY_FRESH_MS

/**
 * "as of 22m ago" once a light's reading is old enough that showing it bare would be a small lie;
 * null while it is still plausibly current.
 */
fun stalenessLabel(fetchedAtMs: Long?, nowMs: Long): String? {
    if (fetchedAtMs == null) return null
    val age = nowMs - fetchedAtMs
    if (age < WIDGET_STALE_AFTER_MS) return null
    val minutes = age / 60_000
    return when {
        minutes < 60 -> "as of ${minutes}m ago"
        minutes < 60 * 24 -> "as of ${minutes / 60}h ago"
        else -> "as of ${minutes / (60 * 24)}d ago"
    }
}

/**
 * The next stop above [currentPct] on the dim ladder, or full brightness at the top. An off light
 * (0%) steps up to the dimmest stop — the toggle is the way to turn it on properly.
 */
fun dimUp(currentPct: Int): Int =
    WIDGET_DIM_STOPS.firstOrNull { it > currentPct } ?: WIDGET_DIM_STOPS.last()

/** The next stop below [currentPct], or the dimmest. Never reaches off — that's the toggle's job. */
fun dimDown(currentPct: Int): Int =
    WIDGET_DIM_STOPS.lastOrNull { it < currentPct } ?: WIDGET_DIM_STOPS.first()

private val LOCK_TRANSITIONAL = setOf("locking", "unlocking")

/**
 * Has HA finished reacting to a widget's command?
 *
 * In-app this is "any echo at all" — the socket keeps streaming, so a transitional `locking` is a
 * fine place to hand the story over to. A widget polls and then stops, so stopping at `locking`
 * would freeze it there until something else happened to redraw it. Hence: poll on through the
 * transitional states to a settled one.
 */
fun widgetEchoSettled(kind: WidgetKind, before: String?, current: String): Boolean {
    if (current == before) return false
    return when (kind) {
        WidgetKind.LOCK -> current !in LOCK_TRANSITIONAL
        WidgetKind.ALARM -> current !in ALARM_TRANSITIONAL
        WidgetKind.LIGHT -> true
        // A temperature widget issues no commands, so nothing ever waits on its
        // echo. Any change is "settled" by definition.
        WidgetKind.TEMPERATURE -> true
    }
}

// ── Light ────────────────────────────────────────────────────────────────────────────────────

data class LightWidgetView(
    val name: String,
    val on: Boolean,
    val dimmable: Boolean,
    val pct: Int,
    /** "On · 60%", "Off", "Unavailable", "Checking…". */
    val stateLabel: String,
    /** False for `unavailable`/`unknown` and before the first fetch — controls are disabled. */
    val controllable: Boolean,
    val pending: Boolean,
    val staleness: String?,
)

fun lightWidgetView(
    snapshot: WidgetSnapshot?,
    nowMs: Long,
    pendingSinceMs: Long? = null,
): LightWidgetView {
    val pending = widgetPending(pendingSinceMs, nowMs)
    if (snapshot == null) {
        return LightWidgetView(
            name = "Light",
            on = false,
            dimmable = false,
            pct = 0,
            stateLabel = "Checking…",
            controllable = false,
            pending = pending,
            staleness = null,
        )
    }
    val on = snapshot.state == "on"
    val settled = snapshot.state == "on" || snapshot.state == "off"
    val dimmable = isDimmableLight(snapshot.attributes)
    val pct = brightnessPct(snapshot.attributes)
    return LightWidgetView(
        name = snapshot.name,
        on = on,
        dimmable = dimmable,
        pct = pct,
        stateLabel = when {
            !settled -> snapshot.state.replaceFirstChar { it.uppercaseChar() }
            on && dimmable && pct > 0 -> "On · $pct%"
            on -> "On"
            else -> "Off"
        },
        controllable = settled,
        pending = pending,
        staleness = stalenessLabel(snapshot.fetchedAtMs, nowMs),
    )
}

/**
 * What a light will look like the instant a command lands, so the widget can draw the result
 * rather than a spinner. Lights get this and locks don't for the same reason `RockerSwitch` is
 * optimistic and `LockVault` is not: a lamp that briefly reads wrong is a cosmetic error, and a
 * confirming read follows within the second either way.
 */
fun predictLight(
    snapshot: WidgetSnapshot,
    service: String,
    extra: Map<String, Any?>,
    nowMs: Long,
): WidgetSnapshot {
    val on = when (service) {
        "turn_on" -> true
        "turn_off" -> false
        else -> snapshot.state != "on"
    }
    val pct = (extra["brightness_pct"] as? Number)?.toInt()
    val attributes = if (on && pct != null) {
        JsonObject(snapshot.attributes + ("brightness" to JsonPrimitive((pct * 2.55).roundToInt())))
    } else {
        snapshot.attributes
    }
    return snapshot.copy(
        state = if (on) "on" else "off",
        attributes = attributes,
        fetchedAtMs = nowMs,
    )
}

// ── Lock ─────────────────────────────────────────────────────────────────────────────────────

data class LockWidgetView(
    val name: String,
    val phase: LockPhase,
    /** "Locked", "Jammed — try again", "Checking…". */
    val label: String,
    /** The button's word: "Lock", "Unlock", "Tap again to unlock", "Unlocking…". */
    val actionLabel: String,
    /** The HA service a tap sends, or null when there is nothing safe to send yet. */
    val service: String?,
    /** True when this tap only arms the confirmation rather than sending anything. */
    val armsConfirm: Boolean,
    val confirming: Boolean,
    val pending: Boolean,
    /** State channel: green when secure, orange when jammed, absent otherwise. */
    val channel: Channel?,
    /** Action channel — the button wears the colour of what it will *do*, as the vault card does. */
    val actionChannel: Channel,
    /** False when the reading is too old to vouch for — the widget is refetching. */
    val known: Boolean,
    /** When this reading was taken, printed beside the state so a persisted frame can't lie. */
    val readAtMs: Long?,
)

// ── Temperature ──────────────────────────────────────────────────────────────────────────────

/** Everything the temperature widget draws, decided here so the Glance layer stays dumb. */
data class TemperatureWidgetView(
    val name: String,
    /** The reading, already formatted — or null when there isn't a usable one. */
    val reading: String?,
    /** `°F` / `°C` as HA reports it; the widget never converts. */
    val unit: String,
    val band: TempBand?,
    /** "Cold" / "Comfortable" / "Warm" / "Hot", so colour is never the only signal. */
    val label: String,
    /** Channel accent, or null for HOT (which uses the error red) and for no reading. */
    val channel: Channel?,
    /** True when this should render in the error red rather than a channel colour. */
    val alert: Boolean,
    /** "as of 22m ago" once the reading is old enough to be worth doubting, else null. */
    val staleness: String?,
)

/**
 * Unlike the lock and alarm, an expired temperature is still shown — with its age.
 *
 * The security widgets hide a stale reading because "Locked" when it isn't is a
 * dangerous lie. A room's temperature does not change in the way a door does, so an
 * hour-old 70° is still useful information as long as the widget says how old it is.
 * That is the same call the light widget makes, and for the same reason.
 */
fun temperatureWidgetView(
    snapshot: WidgetSnapshot?,
    nowMs: Long,
    coldBelow: Double = WIDGET_TEMP_COLD_BELOW_DEFAULT,
    warmAbove: Double = WIDGET_TEMP_WARM_ABOVE_DEFAULT,
    hotAbove: Double = WIDGET_TEMP_HOT_ABOVE_DEFAULT,
): TemperatureWidgetView {
    val name = snapshot?.name ?: "Temperature"
    val unit = snapshot?.attributes?.get("unit_of_measurement")
        ?.let { (it as? JsonPrimitive)?.content } ?: "°"
    val reading = temperatureDisplay(snapshot?.state)
    val value = snapshot?.state?.trim()?.toDoubleOrNull()

    if (reading == null || value == null) {
        return TemperatureWidgetView(
            name = name,
            reading = null,
            unit = unit,
            band = null,
            // Distinguish "never fetched" from "HA says unknown" — the first resolves
            // itself, the second means the sensor is asleep or gone.
            label = if (snapshot == null) "Checking…" else "No reading",
            channel = null,
            alert = false,
            staleness = null,
        )
    }

    val band = temperatureBand(value, coldBelow, warmAbove, hotAbove)
    return TemperatureWidgetView(
        name = name,
        reading = reading,
        unit = unit,
        band = band,
        label = temperatureLabel(band),
        channel = temperatureChannel(band),
        alert = temperatureIsAlert(band),
        staleness = stalenessLabel(snapshot.fetchedAtMs, nowMs),
    )
}

/**
 * Unlocking is the destructive direction: in-app it costs a deliberate slide ([LockVaultView]'s
 * `SlideToAct`), which Glance cannot draw. A confirm tap is the nearest equivalent gesture that a
 * pocket or a misplaced thumb won't produce by accident. Locking stays one tap — the worst case
 * of an accidental lock is a walk to the door.
 */
fun lockActionNeedsConfirm(service: String): Boolean = service == "unlock"

fun lockWidgetView(
    snapshot: WidgetSnapshot?,
    nowMs: Long,
    pendingSinceMs: Long? = null,
    confirmSinceMs: Long? = null,
): LockWidgetView {
    val pending = widgetPending(pendingSinceMs, nowMs)
    val fresh = securityStateFresh(snapshot?.fetchedAtMs, nowMs)
    val name = snapshot?.name ?: "Lock"

    // Unknown-until-proven: a cold render, or a reading past its expiry. Never the last state seen.
    if (snapshot == null || !fresh) {
        return LockWidgetView(
            name = name,
            phase = LockPhase.UNKNOWN,
            label = "Checking…",
            actionLabel = "Checking…",
            service = null,
            armsConfirm = false,
            confirming = false,
            pending = pending,
            channel = null,
            actionChannel = Channel.RECOVERY,
            known = false,
            readAtMs = null,
        )
    }

    val vault = lockVaultView(snapshot.state)
    val confirming = widgetConfirmArmed(confirmSinceMs, nowMs)
    val needsConfirm = lockActionNeedsConfirm(vault.service)
    val actionable = vault.enabled && !vault.transitional && !pending
    return LockWidgetView(
        name = name,
        phase = vault.phase,
        label = vault.label,
        actionLabel = when {
            pending -> vault.pendingLabel
            vault.transitional -> vault.label
            !vault.enabled -> "Unavailable"
            confirming -> "Tap again to unlock"
            vault.phase == LockPhase.JAMMED -> "Retry lock"
            needsConfirm -> "Unlock"
            else -> "Lock"
        },
        // An unlock's first tap sends nothing; it arms the confirmation.
        service = when {
            !actionable -> null
            needsConfirm && !confirming -> null
            else -> vault.service
        },
        armsConfirm = actionable && needsConfirm && !confirming,
        confirming = confirming,
        pending = pending,
        channel = vault.stateChannel,
        actionChannel = vault.actionChannel,
        known = true,
        readAtMs = snapshot.fetchedAtMs,
    )
}

// ── Alarm ────────────────────────────────────────────────────────────────────────────────────

data class AlarmWidgetView(
    val name: String,
    /** "Armed — Away", "Arming…", "Checking…". */
    val label: String,
    /** The `ArmButton.state` currently active, for segment highlighting; null when unknown. */
    val activeState: String?,
    val transitioning: Boolean,
    val pending: Boolean,
    /** The segment awaiting its confirming second tap, or null. */
    val confirmingService: String?,
    val channel: Channel?,
    val known: Boolean,
    /** When this reading was taken — see [LockWidgetView.readAtMs]. */
    val readAtMs: Long?,
)

/** Disarming is the destructive direction — same reasoning as [lockActionNeedsConfirm]. */
fun alarmActionNeedsConfirm(service: String): Boolean = service == "alarm_disarm"

fun alarmWidgetView(
    snapshot: WidgetSnapshot?,
    nowMs: Long,
    pendingSinceMs: Long? = null,
    confirmService: String? = null,
    confirmSinceMs: Long? = null,
): AlarmWidgetView {
    val pending = widgetPending(pendingSinceMs, nowMs)
    val fresh = securityStateFresh(snapshot?.fetchedAtMs, nowMs)
    val name = snapshot?.name ?: "Alarm"

    if (snapshot == null || !fresh) {
        return AlarmWidgetView(
            name = name,
            label = "Checking…",
            activeState = null,
            transitioning = false,
            pending = pending,
            confirmingService = null,
            channel = null,
            known = false,
            readAtMs = null,
        )
    }

    val view = alarmView(snapshot.state)
    val confirming = confirmService?.takeIf { widgetConfirmArmed(confirmSinceMs, nowMs) }
    return AlarmWidgetView(
        name = name,
        label = view.label,
        activeState = snapshot.state.takeIf { s -> ARM_BUTTONS.any { it.state == s } },
        transitioning = view.transitioning,
        pending = pending,
        confirmingService = confirming,
        channel = view.channel,
        known = true,
        readAtMs = snapshot.fetchedAtMs,
    )
}

/**
 * What a tap on an arm segment should do: send the service, arm a confirmation first, or nothing.
 * Re-tapping the state the panel is already in is a no-op rather than a redundant call.
 */
sealed interface ArmTap {
    data class Send(val service: String) : ArmTap
    data class Confirm(val service: String) : ArmTap
    data object Ignore : ArmTap
}

fun armTap(view: AlarmWidgetView, button: ArmButton): ArmTap = when {
    !view.known || view.pending -> ArmTap.Ignore
    view.activeState == button.state && !view.transitioning -> ArmTap.Ignore
    !alarmActionNeedsConfirm(button.service) -> ArmTap.Send(button.service)
    view.confirmingService == button.service -> ArmTap.Send(button.service)
    else -> ArmTap.Confirm(button.service)
}
