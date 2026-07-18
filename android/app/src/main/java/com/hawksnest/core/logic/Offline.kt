package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The honest degraded offline model, pure and unit-testable (no Compose, no clock, no socket).
 *
 * The refined invariant: after an in-session drop, non-security entities may keep rendering —
 * dimmed and labeled "Reconnecting — as of HH:MM" — for at most [GRACE_WINDOW_MS]; lock and
 * alarm state is **never** rendered stale (masked the moment the socket is gone); nothing is
 * ever persisted and no commands are queued. Mirrored by `src/lib/offline.ts` on the web.
 */

/** How long non-security entities may render dimmed + labeled after an in-session drop. */
const val GRACE_WINDOW_MS = 120_000L

/** Domains whose state must never render stale — masked the moment the live socket drops. */
val SECURITY_STALE_DOMAINS: Set<String> = setOf("lock", "alarm_control_panel")

/** True once an in-session drop has outlived the grace window (collapse to the full offline state). */
fun graceExpired(staleSinceMs: Long, nowMs: Long): Boolean =
    nowMs - staleSinceMs >= GRACE_WINDOW_MS

/** Whole seconds until the next reconnect attempt, rounded up, never negative. */
fun retryCountdownSeconds(nextRetryAtMs: Long, nowMs: Long): Long =
    ((nextRetryAtMs - nowMs).coerceAtLeast(0) + 999) / 1_000

/**
 * Short "as of" readout for staleness banners: "3:42 PM" when [thenMs] falls on today,
 * "Jul 16, 3:42 PM" otherwise. [nowMs]/[zone] are injectable so tests are deterministic.
 */
fun formatAsOf(
    thenMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val then = Instant.ofEpochMilli(thenMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val pattern = if (then.toLocalDate() == now.toLocalDate()) "h:mm a" else "MMM d, h:mm a"
    return then.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}

/**
 * Collapse lock/alarm entities to `unavailable` — the security-invariant half of the grace
 * window. Called by `HaState` the moment the live socket is lost, so no screen can render a
 * stale "Locked"/"Armed" even for a moment; a successful reconnect's fresh snapshot restores
 * the real states. `unavailable` is deliberate: every existing presentation (lock cards,
 * the hero, Devices) already treats it as an honest can't-know state with controls disabled.
 * Returns the same map instance when nothing needs masking (no spurious flow emissions).
 */
fun maskSecurityStates(entities: Map<String, HassEntity>): Map<String, HassEntity> {
    val needsMask = entities.values.any {
        domainOf(it.entityId) in SECURITY_STALE_DOMAINS && it.state != "unavailable"
    }
    if (!needsMask) return entities
    return entities.mapValues { (id, e) ->
        if (domainOf(id) in SECURITY_STALE_DOMAINS && e.state != "unavailable") {
            e.copy(state = "unavailable")
        } else {
            e
        }
    }
}
