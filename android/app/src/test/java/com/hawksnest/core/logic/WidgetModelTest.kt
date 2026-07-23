package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home-screen widgets' rules. Most of these exist because a widget can be drawn by a process
 * that has been dead for an hour, which is a failure mode no screen in the app has.
 */
class WidgetModelTest {

    private val now = 1_700_000_000_000L

    private fun snapshot(
        state: String,
        ageMs: Long = 0,
        attributes: JsonObject = JsonObject(emptyMap()),
        entityId: String = "lock.front_door",
        name: String = "Front Door",
    ) = WidgetSnapshot(entityId, name, state, attributes, fetchedAtMs = now - ageMs)

    // ── The security freshness rule ───────────────────────────────────────────────────────────

    @Test
    fun `a lock reading past its expiry is not shown at all`() {
        // The whole point: a widget must never repeat "Locked" from a reading it can't vouch for.
        val stale = lockWidgetView(snapshot("locked", ageMs = WIDGET_SECURITY_FRESH_MS + 1), now)
        assertFalse(stale.known)
        assertEquals("Checking…", stale.label)
        assertNull(stale.service)
        assertNull(stale.channel)
    }

    @Test
    fun `a fresh lock reading is shown`() {
        val fresh = lockWidgetView(snapshot("locked", ageMs = 5_000), now)
        assertTrue(fresh.known)
        assertEquals("Locked", fresh.label)
        assertEquals(Channel.RECOVERY, fresh.channel)
    }

    @Test
    fun `a shown security state always carries the time it was read`() {
        // A widget's frame outlives the reading behind it — nothing redraws the home screen
        // because a value aged out — so the drawn state has to date itself.
        val lock = lockWidgetView(snapshot("locked", ageMs = 5_000), now)
        assertEquals(now - 5_000, lock.readAtMs)
        val alarm = alarmWidgetView(snapshot("armed_away", ageMs = 5_000), now)
        assertEquals(now - 5_000, alarm.readAtMs)
    }

    @Test
    fun `an expired security state carries no read time to print`() {
        assertNull(lockWidgetView(snapshot("locked", ageMs = WIDGET_SECURITY_FRESH_MS + 1), now).readAtMs)
        assertNull(lockWidgetView(null, now).readAtMs)
    }

    @Test
    fun `a cold widget with no reading at all is unknown, not unlocked`() {
        val cold = lockWidgetView(null, now)
        assertFalse(cold.known)
        assertEquals(LockPhase.UNKNOWN, cold.phase)
        assertNull(cold.service)
    }

    @Test
    fun `an alarm reading past its expiry loses its active segment`() {
        val stale = alarmWidgetView(snapshot("armed_away", ageMs = WIDGET_SECURITY_FRESH_MS + 1), now)
        assertFalse(stale.known)
        assertNull(stale.activeState)
    }

    @Test
    fun `a reading stamped in the future is treated as expired, not trusted`() {
        // A clock that moved backwards must not hand a stale state an indefinite lease.
        assertFalse(securityStateFresh(now + 60_000, now))
    }

    // ── Lights: cached, but never silently stale ──────────────────────────────────────────────

    @Test
    fun `a light keeps showing an old reading, with its age attached`() {
        val view = lightWidgetView(
            snapshot("on", ageMs = 22 * 60_000, entityId = "light.kitchen", name = "Kitchen"),
            now,
        )
        assertTrue(view.controllable)
        assertEquals("as of 22m ago", view.staleness)
    }

    @Test
    fun `a recent light reading carries no age note`() {
        assertNull(lightWidgetView(snapshot("on", ageMs = 60_000), now).staleness)
    }

    @Test
    fun `staleness scales past an hour and past a day`() {
        assertEquals("as of 3h ago", stalenessLabel(now - 3 * 3_600_000, now))
        assertEquals("as of 2d ago", stalenessLabel(now - 2 * 86_400_000, now))
    }

    @Test
    fun `a dimmable light reads out its level, an on-off one does not`() {
        val dimmable = buildJsonObject {
            putJsonArray("supported_color_modes") { add("brightness") }
            put("brightness", 128)
        }
        assertEquals("On · 50%", lightWidgetView(snapshot("on", attributes = dimmable), now).stateLabel)

        val onOff = buildJsonObject {
            putJsonArray("supported_color_modes") { add("onoff") }
        }
        val view = lightWidgetView(snapshot("on", attributes = onOff), now)
        assertFalse(view.dimmable)
        assertEquals("On", view.stateLabel)
    }

    @Test
    fun `an unavailable light cannot be controlled`() {
        val view = lightWidgetView(snapshot("unavailable"), now)
        assertFalse(view.controllable)
        assertEquals("Unavailable", view.stateLabel)
    }

    // ── Pending and confirm both expire on their own ──────────────────────────────────────────

    @Test
    fun `a pending marker left behind by a killed process expires`() {
        // Nothing else clears it: the coroutine that would have is gone with the process.
        assertTrue(widgetPending(now - 1_000, now))
        assertFalse(widgetPending(now - WIDGET_ECHO_TIMEOUT_MS - 1, now))
        assertFalse(widgetPending(null, now))
    }

    @Test
    fun `a confirm tap lapses after its window`() {
        assertTrue(widgetConfirmArmed(now - 1_000, now))
        assertFalse(widgetConfirmArmed(now - WIDGET_CONFIRM_WINDOW_MS - 1, now))
    }

    // ── Unlocking takes two taps ──────────────────────────────────────────────────────────────

    @Test
    fun `the first tap on a locked door arms a confirmation and sends nothing`() {
        val view = lockWidgetView(snapshot("locked"), now)
        assertTrue(view.armsConfirm)
        assertNull(view.service)
        assertEquals("Unlock", view.actionLabel)
    }

    @Test
    fun `the second tap sends the unlock`() {
        val view = lockWidgetView(snapshot("locked"), now, confirmSinceMs = now - 500)
        assertTrue(view.confirming)
        assertEquals("unlock", view.service)
        assertEquals("Tap again to unlock", view.actionLabel)
        assertFalse(view.armsConfirm)
    }

    @Test
    fun `locking is a single tap`() {
        val view = lockWidgetView(snapshot("unlocked"), now)
        assertFalse(view.armsConfirm)
        assertEquals("lock", view.service)
        assertEquals("Lock", view.actionLabel)
    }

    @Test
    fun `a jam reads as a failure and offers a retry, never as unlocked`() {
        val view = lockWidgetView(snapshot("jammed"), now)
        assertEquals("Jammed — try again", view.label)
        assertEquals("Retry lock", view.actionLabel)
        assertEquals(Channel.STREAK, view.channel)
        assertEquals("lock", view.service)
    }

    @Test
    fun `a lock mid-transition takes no new command`() {
        val view = lockWidgetView(snapshot("locking"), now)
        assertNull(view.service)
        assertEquals("Locking…", view.actionLabel)
    }

    @Test
    fun `a pending lock shows the action in flight and accepts nothing`() {
        val view = lockWidgetView(snapshot("locked"), now, pendingSinceMs = now - 500)
        assertTrue(view.pending)
        assertEquals("Unlocking…", view.actionLabel)
        assertNull(view.service)
    }

    @Test
    fun `an unavailable lock offers no action`() {
        val view = lockWidgetView(snapshot("unavailable"), now)
        assertEquals("Unavailable", view.actionLabel)
        assertNull(view.service)
    }

    // ── Arming is one tap, disarming is two ───────────────────────────────────────────────────

    private fun armButton(service: String) = ARM_BUTTONS.first { it.service == service }

    @Test
    fun `arming away sends straight away`() {
        val view = alarmWidgetView(snapshot("disarmed"), now)
        assertEquals(ArmTap.Send("alarm_arm_away"), armTap(view, armButton("alarm_arm_away")))
    }

    @Test
    fun `disarming asks first`() {
        val view = alarmWidgetView(snapshot("armed_away"), now)
        assertEquals(ArmTap.Confirm("alarm_disarm"), armTap(view, armButton("alarm_disarm")))
    }

    @Test
    fun `disarming goes through once confirmed`() {
        val view = alarmWidgetView(
            snapshot("armed_away"),
            now,
            confirmService = "alarm_disarm",
            confirmSinceMs = now - 500,
        )
        assertEquals(ArmTap.Send("alarm_disarm"), armTap(view, armButton("alarm_disarm")))
    }

    @Test
    fun `tapping the state the panel is already in does nothing`() {
        val view = alarmWidgetView(snapshot("armed_home"), now)
        assertEquals(ArmTap.Ignore, armTap(view, armButton("alarm_arm_home")))
    }

    @Test
    fun `no segment accepts a tap while a command is settling`() {
        val view = alarmWidgetView(snapshot("armed_away"), now, pendingSinceMs = now - 500)
        ARM_BUTTONS.forEach { assertEquals(ArmTap.Ignore, armTap(view, it)) }
    }

    @Test
    fun `no segment accepts a tap while the state is unknown`() {
        val view = alarmWidgetView(null, now)
        ARM_BUTTONS.forEach { assertEquals(ArmTap.Ignore, armTap(view, it)) }
    }

    @Test
    fun `an exit delay is shown as itself`() {
        val view = alarmWidgetView(snapshot("arming"), now)
        assertTrue(view.transitioning)
        assertEquals("Arming…", view.label)
        assertNull(view.activeState)
    }

    // ── Echo: poll on through transitional states ─────────────────────────────────────────────

    @Test
    fun `a lock is not settled while it is still turning`() {
        assertFalse(widgetEchoSettled(WidgetKind.LOCK, before = "locked", current = "unlocking"))
        assertTrue(widgetEchoSettled(WidgetKind.LOCK, before = "locked", current = "unlocked"))
    }

    @Test
    fun `a jam ends the wait — it is an answer`() {
        assertTrue(widgetEchoSettled(WidgetKind.LOCK, before = "unlocked", current = "jammed"))
    }

    @Test
    fun `an alarm is not settled during its exit delay`() {
        assertFalse(widgetEchoSettled(WidgetKind.ALARM, before = "disarmed", current = "arming"))
        assertTrue(widgetEchoSettled(WidgetKind.ALARM, before = "disarmed", current = "armed_away"))
    }

    @Test
    fun `an unchanged state is never settled`() {
        assertFalse(widgetEchoSettled(WidgetKind.LIGHT, before = "on", current = "on"))
        assertTrue(widgetEchoSettled(WidgetKind.LIGHT, before = "on", current = "off"))
    }

    // ── Dim steps ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stepping down stops short of off`() {
        // Turning the light off is the toggle's job; brightness_pct 0 isn't portable across
        // integrations, which is why dimCommit treats it specially.
        assertEquals(WIDGET_DIM_FLOOR_PCT, stepBrightnessPct(10, -WIDGET_DIM_STEP_PCT))
        assertEquals(WIDGET_DIM_FLOOR_PCT, stepBrightnessPct(WIDGET_DIM_FLOOR_PCT, -WIDGET_DIM_STEP_PCT))
    }

    @Test
    fun `stepping up stops at full`() {
        assertEquals(100, stepBrightnessPct(90, WIDGET_DIM_STEP_PCT))
    }

    @Test
    fun `a step from an off light lands on a real level`() {
        assertEquals(WIDGET_DIM_STEP_PCT, stepBrightnessPct(0, WIDGET_DIM_STEP_PCT))
    }

    @Test
    fun `every clamped step commits as turn_on, never turn_off`() {
        val (service, _) = dimCommit(stepBrightnessPct(10, -WIDGET_DIM_STEP_PCT))
        assertEquals("turn_on", service)
    }

    // ── Optimistic light prediction ───────────────────────────────────────────────────────────

    @Test
    fun `predicting an on with a level sets both state and brightness`() {
        val predicted = predictLight(
            snapshot("off", entityId = "light.kitchen"),
            service = "turn_on",
            extra = mapOf("brightness_pct" to 40),
            nowMs = now,
        )
        assertEquals("on", predicted.state)
        assertEquals(40, brightnessPct(predicted.attributes))
        assertEquals(now, predicted.fetchedAtMs)
    }

    @Test
    fun `predicting a toggle flips the state`() {
        assertEquals("off", predictLight(snapshot("on"), "toggle", emptyMap(), now).state)
        assertEquals("on", predictLight(snapshot("off"), "toggle", emptyMap(), now).state)
    }

    // ── Blockers ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every blocker says something actionable`() {
        WidgetBlocker.entries.forEach { blocker ->
            val copy = blockerCopy(blocker)
            assertTrue(copy.headline.isNotBlank(), "$blocker has no headline")
            assertTrue(copy.detail.isNotBlank(), "$blocker has no detail")
        }
    }

    @Test
    fun `the unreachable message names the thing to check`() {
        // On this setup an unreachable HA is nearly always the tailnet being down.
        assertTrue(blockerCopy(WidgetBlocker.UNREACHABLE).detail.contains("Tailscale"))
    }

    @Test
    fun `a light widget can be pointed at a switch`() {
        assertTrue("switch" in widgetCandidateDomains(WidgetKind.LIGHT))
        assertEquals(setOf("alarm_control_panel"), widgetCandidateDomains(WidgetKind.ALARM))
    }
}
