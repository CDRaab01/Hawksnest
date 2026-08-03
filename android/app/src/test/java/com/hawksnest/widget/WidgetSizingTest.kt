package com.hawksnest.widget

import com.hawksnest.core.logic.WIDGET_MIN_WIDTH_DP
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the widget sizing contract, which is split across two files that must agree.
 *
 * `minResizeWidth` in the XML says how small the launcher may make a widget. The
 * `SizeMode.Responsive` bucket set says which layouts Glance has to choose from, and it picks the
 * largest bucket that FITS inside the real size. Lower one without the other and the launcher
 * hands a widget 40dp while the content is laid out for 110dp — which clips rather than reflows,
 * and does so only on a device, only after a manual resize. Exactly the kind of thing nobody
 * finds until it is on a phone.
 *
 * This is also the regression that produced the original complaint: widgets arriving too large on
 * a coarse launcher grid and refusing to shrink.
 */
class WidgetSizingTest {

    private val moduleRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { if (File(it, "src/main/res/xml").isDirectory) it else File(it, "android/app") }
            .firstOrNull { File(it, "src/main/res/xml").isDirectory }
            ?: error("Could not locate the app module from ${File("").absolutePath}")
    }

    private fun infoFiles(): List<File> =
        File(moduleRoot, "src/main/res/xml").listFiles { f -> f.name.endsWith("_widget_info.xml") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun attr(xml: String, name: String): Int? =
        Regex("""android:$name="(\d+)(?:dp)?"""").find(xml)?.groupValues?.get(1)?.toInt()

    @Test
    fun `every widget declares the attributes resizing depends on`() {
        val files = infoFiles()
        assertEquals("expected six widget providers", 6, files.size)
        for (f in files) {
            val xml = f.readText()
            for (a in listOf(
                "minWidth", "minHeight", "targetCellWidth", "targetCellHeight",
                "minResizeWidth", "minResizeHeight", "maxResizeWidth", "maxResizeHeight",
            )) {
                assertTrue("${f.name} is missing android:$a", attr(xml, a) != null)
            }
            assertTrue(
                "${f.name} must be resizable in both directions",
                xml.contains("""android:resizeMode="horizontal|vertical""""),
            )
        }
    }

    @Test
    fun `the resize floor is never above the default size`() {
        // If minResize* exceeds min*, the launcher offers no shrink handle at all — the widget
        // arrives at its size and stays there, which is the bug this all started from.
        for (f in infoFiles()) {
            val xml = f.readText()
            assertTrue(
                "${f.name}: minResizeWidth must not exceed minWidth",
                attr(xml, "minResizeWidth")!! <= attr(xml, "minWidth")!!,
            )
            assertTrue(
                "${f.name}: minResizeHeight must not exceed minHeight",
                attr(xml, "minResizeHeight")!! <= attr(xml, "minHeight")!!,
            )
        }
    }

    @Test
    fun `nothing arrives more than one row tall except the scene pad`() {
        // A five-key pad genuinely needs the room. Everything else is a header plus one control
        // row, and arriving two rows tall is what made these feel oversized on a coarse grid.
        for (f in infoFiles()) {
            val h = attr(f.readText(), "targetCellHeight")!!
            if (f.name.startsWith("scene_pad")) {
                assertTrue("${f.name}: pad should still arrive tall", h >= 2)
            } else {
                assertEquals("${f.name} should arrive one row tall", 1, h)
            }
        }
    }

    @Test
    fun `a widget that can shrink to one cell also has a bucket that narrow`() {
        // The two-file contract. Android's grid is 70n-30, so one cell is 40dp.
        val srcRoot = File(moduleRoot, "src/main/java/com/hawksnest/widget")
        for (f in infoFiles()) {
            val floor = attr(f.readText(), "minResizeWidth")!!
            if (floor > WIDGET_MIN_WIDTH_DP) continue // declares a wider floor on purpose
            val kotlinName = f.name.removeSuffix("_widget_info.xml")
                .split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Widget.kt"
            val src = File(srcRoot, kotlinName)
            assertTrue("expected $kotlinName to exist", src.exists())
            assertTrue(
                "$kotlinName allows a ${floor}dp placement but declares no bucket that narrow — " +
                    "Glance would lay it out for a width it does not have and clip",
                src.readText().contains("WIDGET_MIN_WIDTH_DP"),
            )
        }
    }

    @Test
    fun `one cell is forty dp, per Android's grid formula`() {
        // 70n - 30: one cell 40dp, two 110dp, three 180dp. The XML floors are derived from this,
        // so if it ever changes the floors are wrong rather than merely suboptimal.
        assertEquals(40, WIDGET_MIN_WIDTH_DP)
    }
}
