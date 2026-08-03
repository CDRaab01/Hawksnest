package com.hawksnest.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hawksnest.MainActivity
import com.hawksnest.R
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.AppShortcut
import com.hawksnest.core.logic.ID_ARM_AWAY
import com.hawksnest.core.logic.ID_ARM_HOME
import com.hawksnest.core.logic.ID_LOCK_UP
import com.hawksnest.core.logic.ShortcutAction
import com.hawksnest.core.logic.lockEntityIds
import com.hawksnest.core.logic.shortcutsFor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes launcher shortcuts (long-press the app icon) and performs them.
 *
 * **Dynamic, not static XML.** A static shortcut has to name its entity at build time, and these
 * ids are per-install — the same reason widgets ask which device they control rather than
 * assuming. Dynamic shortcuts are built from the entities that actually exist, so an install with
 * no alarm panel simply doesn't offer "Arm away" rather than offering one that fails.
 *
 * The cost is that they appear only after the app has connected once. That is the right trade:
 * a shortcut that silently does nothing is worse than one that isn't there yet.
 *
 * Which shortcuts are safe to offer at all is decided in `core/logic/shortcutsFor` — see the note
 * there on why nothing that unlocks or disarms appears.
 */
@Singleton
class ShortcutPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: ConnectionManager,
) {
    /** Republish whenever the entity set changes shape (not on every state tick). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            connection.state.entities
                .map { entities -> shortcutsFor(entities.values) }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
    }

    private fun publish(shortcuts: List<AppShortcut>) {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                shortcuts.map { s ->
                    ShortcutInfoCompat.Builder(context, s.id)
                        .setShortLabel(s.label)
                        .setLongLabel(s.label)
                        .setIcon(IconCompat.createWithResource(context, iconFor(s.id)))
                        .setIntent(
                            Intent(context, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                putExtra(EXTRA_SHORTCUT, s.id)
                            },
                        )
                        .build()
                },
            )
        }
    }

    /**
     * Run a shortcut. Called from `MainActivity` once the app is up.
     *
     * Goes through [ConnectionManager.control] like every other user-initiated call, so a failure
     * lands on the same app-level snackbar with the same reject haptic instead of failing silently
     * — a shortcut tap must not be a quieter way to call a service than pressing the button.
     */
    suspend fun perform(id: String) {
        when (val action = resolve(id)) {
            null -> Unit
            ShortcutAction.LockUp ->
                lockEntityIds(connection.state.entities.value.values).forEach { entityId ->
                    connection.control(entityId, "lock", "Lock")
                }
            is ShortcutAction.ArmAway ->
                connection.control(action.entityId, "alarm_arm_away", "Arm away")
            is ShortcutAction.ArmHome ->
                connection.control(action.entityId, "alarm_arm_home", "Arm home")
        }
    }

    private fun resolve(id: String): ShortcutAction? =
        shortcutsFor(connection.state.entities.value.values).firstOrNull { it.id == id }?.action

    private fun iconFor(id: String): Int = when (id) {
        ID_LOCK_UP -> R.drawable.ic_shortcut_lock
        ID_ARM_AWAY -> R.drawable.ic_shortcut_shield
        ID_ARM_HOME -> R.drawable.ic_shortcut_home
        else -> R.drawable.ic_shortcut_lock
    }

    companion object {
        const val EXTRA_SHORTCUT = "com.hawksnest.SHORTCUT"
    }
}
