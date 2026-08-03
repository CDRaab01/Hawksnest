package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity

/**
 * A launcher shortcut this app offers, and what it does when tapped.
 *
 * [id] is stable across publishes — the launcher keys pinned shortcuts on it, so renaming one
 * orphans anything the user pinned.
 */
data class AppShortcut(
    val id: String,
    val label: String,
    val action: ShortcutAction,
)

sealed interface ShortcutAction {
    /** Lock every lock entity. */
    data object LockUp : ShortcutAction

    /** Arm the alarm in away mode. */
    data class ArmAway(val entityId: String) : ShortcutAction

    /** Arm the alarm in home mode. */
    data class ArmHome(val entityId: String) : ShortcutAction
}

/**
 * Which shortcuts to publish for the current entity set.
 *
 * **Only safe-direction actions appear here, and that is a security decision rather than a scope
 * one.** A launcher shortcut is a single tap from the home screen with no confirmation and no
 * app in the foreground. Locking a door and arming an alarm fail safe: the worst case of an
 * accidental tap is a locked door. Unlocking and disarming do not, which is why the in-app
 * controls make you slide (`SlideToAct`) and the widgets make you tap twice — a pocket must not
 * be able to open the front door. Adding "Unlock" here would quietly undo both of those.
 *
 * A navigation-only shortcut was considered and dropped: cameras live on Home, so "Cameras"
 * would have done exactly what tapping the icon already does.
 *
 * Returns at most three: launchers commonly surface four or five and reserve one, and a long-press
 * menu that needs scrolling defeats the point.
 */
fun shortcutsFor(entities: Collection<HassEntity>): List<AppShortcut> {
    val out = mutableListOf<AppShortcut>()

    if (entities.any { it.entityId.startsWith("lock.") }) {
        out += AppShortcut(ID_LOCK_UP, "Lock up", ShortcutAction.LockUp)
    }
    // The first alarm panel by entity id, so the choice is stable across restarts rather than
    // depending on map iteration order.
    entities.filter { it.entityId.startsWith("alarm_control_panel.") }
        .minByOrNull { it.entityId }
        ?.let { panel ->
            out += AppShortcut(ID_ARM_AWAY, "Arm away", ShortcutAction.ArmAway(panel.entityId))
            out += AppShortcut(ID_ARM_HOME, "Arm home", ShortcutAction.ArmHome(panel.entityId))
        }
    return out.take(MAX_SHORTCUTS)
}

/** Every lock the "Lock up" shortcut should act on. */
fun lockEntityIds(entities: Collection<HassEntity>): List<String> =
    entities.map { it.entityId }.filter { it.startsWith("lock.") }.sorted()

const val ID_LOCK_UP = "lock_up"
const val ID_ARM_AWAY = "arm_away"
const val ID_ARM_HOME = "arm_home"

private const val MAX_SHORTCUTS = 3
