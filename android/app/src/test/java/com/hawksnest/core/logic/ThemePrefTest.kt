package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePrefTest {

    @Test
    fun `explicit choices override the system, System defers to it`() {
        assertTrue(resolveDarkTheme(ThemePref.Dark, systemIsDark = false))
        assertFalse(resolveDarkTheme(ThemePref.Light, systemIsDark = true))
        assertTrue(resolveDarkTheme(ThemePref.System, systemIsDark = true))
        assertFalse(resolveDarkTheme(ThemePref.System, systemIsDark = false))
    }

    @Test
    fun `the default is System, preserving what Android already did`() {
        // Not Dark, which is web's default. Android has always followed the system setting, and
        // changing that silently would flip the look for anyone who already had the app.
        assertEquals(ThemePref.System, ThemePref.DEFAULT)
    }

    @Test
    fun `stored values round-trip, case-insensitively`() {
        for (p in ThemePref.entries) assertEquals(p, ThemePref.parse(p.name))
        assertEquals(ThemePref.Light, ThemePref.parse("light"))
        assertEquals(ThemePref.Dark, ThemePref.parse("DARK"))
    }

    @Test
    fun `an unreadable stored value falls back rather than throwing`() {
        // A bad value in DataStore must not brick the UI — there would be no way to reach the
        // picker to fix it.
        assertEquals(ThemePref.DEFAULT, ThemePref.parse(null))
        assertEquals(ThemePref.DEFAULT, ThemePref.parse(""))
        assertEquals(ThemePref.DEFAULT, ThemePref.parse("sepia"))
    }
}
