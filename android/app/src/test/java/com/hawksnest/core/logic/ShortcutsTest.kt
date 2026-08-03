package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {

    private fun entities(vararg ids: String) = ids.map { entity(it) }

    @Test
    fun `nothing that unlocks or disarms is ever offered`() {
        // The security property this whole file exists to protect. A launcher shortcut is one tap
        // with no confirmation and no app in the foreground; the in-app control makes you slide
        // and the widget makes you tap twice, precisely so a pocket cannot open the front door.
        val all = shortcutsFor(
            entities("lock.front_door", "lock.back_door", "alarm_control_panel.home", "camera.porch"),
        )
        assertTrue(
            "found an unlock/disarm shortcut: $all",
            all.none { it.label.contains("unlock", true) || it.label.contains("disarm", true) },
        )
        // Every action must be one of the three safe kinds — a new ShortcutAction that unlocks
        // would fail here rather than quietly shipping.
        assertTrue(
            "unexpected action kind in $all",
            all.all {
                it.action is ShortcutAction.LockUp ||
                    it.action is ShortcutAction.ArmAway ||
                    it.action is ShortcutAction.ArmHome
            },
        )
        // And positively: only the three safe ones.
        assertEquals(listOf(ID_LOCK_UP, ID_ARM_AWAY, ID_ARM_HOME), all.map { it.id })
    }

    @Test
    fun `no locks means no Lock up shortcut`() {
        val ids = shortcutsFor(entities("light.kitchen", "alarm_control_panel.home")).map { it.id }
        assertEquals(listOf(ID_ARM_AWAY, ID_ARM_HOME), ids)
    }

    @Test
    fun `no alarm panel means no arm shortcuts`() {
        val ids = shortcutsFor(entities("lock.front_door")).map { it.id }
        assertEquals(listOf(ID_LOCK_UP), ids)
    }

    @Test
    fun `an install with neither offers nothing rather than something broken`() {
        assertEquals(emptyList<AppShortcut>(), shortcutsFor(entities("light.kitchen")))
        assertEquals(emptyList<AppShortcut>(), shortcutsFor(emptyList()))
    }

    @Test
    fun `the chosen alarm panel is stable, not map-order dependent`() {
        val forwards = shortcutsFor(entities("alarm_control_panel.zone_b", "alarm_control_panel.aaa"))
        val backwards = shortcutsFor(entities("alarm_control_panel.aaa", "alarm_control_panel.zone_b"))
        assertEquals(forwards.map { it.action }, backwards.map { it.action })
        assertEquals(
            ShortcutAction.ArmAway("alarm_control_panel.aaa"),
            forwards.first { it.id == ID_ARM_AWAY }.action,
        )
    }

    @Test
    fun `never publishes more than the launcher will show`() {
        val many = shortcutsFor(
            entities("lock.a", "lock.b", "alarm_control_panel.a", "camera.a", "light.a"),
        )
        assertTrue("published ${many.size}", many.size <= 3)
    }

    @Test
    fun `Lock up acts on every lock, in a stable order`() {
        val locks = lockEntityIds(entities("lock.side", "light.x", "lock.front", "camera.y"))
        assertEquals(listOf("lock.front", "lock.side"), locks)
    }

    @Test
    fun `shortcut ids are stable strings, because launchers pin them`() {
        // Renaming one orphans anything the user pinned to their home screen, so these are
        // pinned here as literals rather than derived.
        assertEquals("lock_up", ID_LOCK_UP)
        assertEquals("arm_away", ID_ARM_AWAY)
        assertEquals("arm_home", ID_ARM_HOME)
    }
}
