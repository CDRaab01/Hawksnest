package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
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
enum class WidgetKind { LIGHT, LOCK, ALARM }

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

fun sizeTier(heightDp: Int): WidgetSizeTier =
    if (heightDp < WIDGET_FULL_MIN_HEIGHT_DP) WidgetSizeTier.COMPACT else WidgetSizeTier.FULL

/**
 * Does the compact single line spend itself on the device's name?
 *
 * For a light, yes — "which lamp is this?" is the only thing that line has to answer, and a lamp
 * shown wrong is a cosmetic error. For a lock or the alarm it must not: that line has to carry the
 * state *and* the time it was read, because those are what stop a frame left on the home screen
 * from quietly lying (see [LockWidgetView.readAtMs]). The name is the part that can be inferred
 * from where the widget sits; the timestamp isn't.
 */
fun compactShowsName(kind: WidgetKind): Boolean = kind == WidgetKind.LIGHT

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
            isPrimaryEntity(entity.entityId, categories)
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
