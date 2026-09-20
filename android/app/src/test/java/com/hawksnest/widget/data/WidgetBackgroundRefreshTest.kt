package com.hawksnest.widget.data

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.WidgetScheduleAction
import com.hawksnest.core.logic.WidgetSnapshot
import com.hawksnest.core.logic.widgetPrintsReadTime
import com.hawksnest.core.logic.widgetRefreshFailureNeedsRedraw
import com.hawksnest.core.logic.widgetRefreshNeedsRedraw
import com.hawksnest.core.logic.widgetScheduleAction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions behind the widgets' one periodic background pass. The pass itself is bound to
 * Glance and WorkManager and is proven on a phone; what it *decides* — draw or don't, ask or
 * don't, keep the schedule or replace it — is all here, because each wrong answer is silent:
 * a needless redraw is a wakelock nobody sees, and a skipped one is a widget that quietly lies.
 */
class WidgetBackgroundRefreshTest {

    private fun snapshot(
        state: String,
        fetchedAtMs: Long = 1_000,
        attributes: Map<String, Int> = emptyMap(),
        name: String = "Lamp",
    ) = WidgetSnapshot(
        entityId = "light.lamp",
        name = name,
        state = state,
        attributes = JsonObject(attributes.mapValues { JsonPrimitive(it.value) }),
        fetchedAtMs = fetchedAtMs,
    )

    // ── draw, or don't ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an unchanged lamp is not redrawn even though the read time moved`() {
        val shown = snapshot("off", fetchedAtMs = 1_000)
        val fresh = snapshot("off", fetchedAtMs = 1_801_000)
        for (kind in listOf(WidgetKind.LIGHT, WidgetKind.SWITCH, WidgetKind.SCENE_PAD)) {
            assertFalse(widgetRefreshNeedsRedraw(kind, shown, fresh, blockerShown = false), "$kind")
        }
    }

    @Test
    fun `a changed state, attribute or name is redrawn`() {
        val shown = snapshot("on", attributes = mapOf("brightness" to 120))
        assertTrue(widgetRefreshNeedsRedraw(WidgetKind.LIGHT, shown, snapshot("off"), false))
        assertTrue(
            widgetRefreshNeedsRedraw(
                WidgetKind.LIGHT, shown, snapshot("on", attributes = mapOf("brightness" to 40)), false,
            )
        )
        assertTrue(
            widgetRefreshNeedsRedraw(
                WidgetKind.LIGHT,
                shown,
                snapshot("on", attributes = mapOf("brightness" to 120), name = "Reading lamp"),
                false,
            )
        )
    }

    @Test
    fun `a widget with nothing drawn, or an error drawn, is always redrawn`() {
        val same = snapshot("off")
        assertTrue(widgetRefreshNeedsRedraw(WidgetKind.LIGHT, null, same, blockerShown = false))
        assertTrue(widgetRefreshNeedsRedraw(WidgetKind.LIGHT, same, same, blockerShown = true))
    }

    @Test
    fun `kinds that print their read time redraw on every successful read`() {
        val shown = snapshot("locked", fetchedAtMs = 1_000)
        val fresh = snapshot("locked", fetchedAtMs = 1_801_000)
        val stamped = WidgetKind.entries.filter(::widgetPrintsReadTime)
        assertEquals(
            setOf(WidgetKind.LOCK, WidgetKind.ALARM, WidgetKind.GARAGE, WidgetKind.TEMPERATURE),
            stamped.toSet(),
        )
        for (kind in stamped) {
            assertTrue(widgetRefreshNeedsRedraw(kind, shown, fresh, blockerShown = false), "$kind")
        }
    }

    @Test
    fun `a failure is drawn once, not once per period`() {
        assertTrue(widgetRefreshFailureNeedsRedraw(shown = null, failure = WidgetBlocker.UNREACHABLE))
        assertFalse(widgetRefreshFailureNeedsRedraw(WidgetBlocker.UNREACHABLE, WidgetBlocker.UNREACHABLE))
        // A different failure is different pixels.
        assertTrue(widgetRefreshFailureNeedsRedraw(WidgetBlocker.UNREACHABLE, WidgetBlocker.UNAUTHORIZED))
    }

    // ── ask, or don't ────────────────────────────────────────────────────────────────────────

    private fun entity(id: String) = HassEntity(entityId = id, state = "on")

    @Test
    fun `widgets on the same entity share one read`() = runTest {
        val asked = mutableListOf<String>()
        val reader = WidgetBatchReader { id -> asked += id; HaCall.Ok(entity(id)) }
        reader.state("light.lamp")
        reader.state("lock.front")
        reader.state("light.lamp")
        assertEquals(listOf("light.lamp", "lock.front"), asked)
    }

    @Test
    fun `an unreachable HA is asked once, not once per widget`() = runTest {
        val asked = mutableListOf<String>()
        val reader = WidgetBatchReader { id -> asked += id; HaCall.Failed(WidgetBlocker.UNREACHABLE) }
        val results = listOf("light.a", "light.b", "lock.c").map { reader.state(it) }
        assertEquals(listOf("light.a"), asked)
        assertTrue(results.all { it == HaCall.Failed(WidgetBlocker.UNREACHABLE) })
    }

    @Test
    fun `a missing entity is that widget's problem only`() = runTest {
        val asked = mutableListOf<String>()
        val reader = WidgetBatchReader { id ->
            asked += id
            if (id == "light.gone") HaCall.Failed(WidgetBlocker.ENTITY_MISSING) else HaCall.Ok(entity(id))
        }
        assertEquals(HaCall.Failed(WidgetBlocker.ENTITY_MISSING), reader.state("light.gone"))
        assertTrue(reader.state("light.here") is HaCall.Ok)
        assertEquals(listOf("light.gone", "light.here"), asked)
    }

    // ── keep the schedule, or replace it ─────────────────────────────────────────────────────

    @Test
    fun `no widgets placed cancels the job whatever was recorded`() {
        assertEquals(WidgetScheduleAction.CANCEL, widgetScheduleAction(0, "v1|30m|CONNECTED", "v1|30m|CONNECTED"))
        assertEquals(WidgetScheduleAction.CANCEL, widgetScheduleAction(0, null, "v1|30m|CONNECTED"))
    }

    @Test
    fun `an unchanged schedule is kept so a process start never resets its timing`() {
        assertEquals(WidgetScheduleAction.KEEP, widgetScheduleAction(9, "v1|30m|CONNECTED", "v1|30m|CONNECTED"))
    }

    @Test
    fun `a first enqueue or a changed schedule replaces instead of silently keeping the old one`() {
        assertEquals(WidgetScheduleAction.REPLACE, widgetScheduleAction(1, null, "v1|30m|CONNECTED"))
        assertEquals(WidgetScheduleAction.REPLACE, widgetScheduleAction(9, "v1|30m|CONNECTED", "v2|60m|CONNECTED"))
    }
}
