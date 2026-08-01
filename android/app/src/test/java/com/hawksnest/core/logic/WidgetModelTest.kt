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
    fun `the ladder is finer at the dim end than the bright end`() {
        // The whole point of stops over a fixed percentage: the eye reads brightness roughly
        // logarithmically, so 80→65 is a smaller perceived change than 20→10 despite being bigger.
        val gapsLow = WIDGET_DIM_STOPS.zipWithNext().take(3).map { (a, b) -> b - a }
        val gapsHigh = WIDGET_DIM_STOPS.zipWithNext().takeLast(3).map { (a, b) -> b - a }
        assertTrue(gapsLow.max() < gapsHigh.min(), "expected tighter steps at the bottom")
    }

    @Test
    fun `stepping down walks the ladder and stops short of off`() {
        // Turning the light off is the toggle's job; brightness_pct 0 isn't portable across
        // integrations, which is why dimCommit treats it specially.
        assertEquals(65, dimDown(80))
        assertEquals(5, dimDown(10))
        assertEquals(1, dimDown(5))
        assertEquals(1, dimDown(1))
    }

    @Test
    fun `stepping up walks the ladder and stops at full`() {
        assertEquals(30, dimUp(20))
        assertEquals(100, dimUp(80))
        assertEquals(100, dimUp(100))
    }

    @Test
    fun `a level between stops snaps to the neighbouring one`() {
        // HA reports whatever the bulb is actually at, which need not be one of our stops.
        assertEquals(50, dimUp(43))
        assertEquals(40, dimDown(43))
    }

    @Test
    fun `stepping up from an off light lands on the dimmest stop`() {
        assertEquals(WIDGET_DIM_STOPS.first(), dimUp(0))
    }

    @Test
    fun `every step commits as turn_on, never turn_off`() {
        WIDGET_DIM_STOPS.forEach { stop ->
            assertEquals("turn_on", dimCommit(stop).first, "stop $stop")
        }
    }

    @Test
    fun `the ladder is ordered, positive and reaches full`() {
        assertEquals(WIDGET_DIM_STOPS.sorted(), WIDGET_DIM_STOPS)
        assertTrue(WIDGET_DIM_STOPS.first() >= 1)
        assertEquals(100, WIDGET_DIM_STOPS.last())
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

    // ── Size tiers ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a one-row widget lays out compact and a two-row one does not`() {
        // A launcher row is roughly 40-100dp depending on grid; the full layout needs a two-line
        // header over a 48dp control, which does not fit in one.
        assertEquals(WidgetSizeTier.COMPACT, sizeTier(40))
        assertEquals(WidgetSizeTier.COMPACT, sizeTier(90))
        assertEquals(WidgetSizeTier.FULL, sizeTier(WIDGET_FULL_MIN_HEIGHT_DP))
        assertEquals(WidgetSizeTier.FULL, sizeTier(180))
    }

    @Test
    fun `a wide compact lock names itself on the same line`() {
        // The original bug: a lock squeezed to one row read "Locked · 7:45 PM" and never said which
        // door, even at the three-cell width it is placed at by default.
        val short = WIDGET_COMPACT_BUCKET_DP
        assertEquals(CompactName.INLINE, compactNamePlacement(WidgetKind.LOCK, 200, short))
        assertEquals(CompactName.INLINE, compactNamePlacement(WidgetKind.ALARM, 250, short))
    }

    @Test
    fun `a narrow compact lock names itself on a second line instead`() {
        // The follow-up bug: gating only on width meant a genuinely small widget still couldn't say
        // which door, even though the button under it had height to spare. Narrow is not the same
        // as no room — room has two dimensions, and the height is usually the one going spare.
        assertEquals(
            CompactName.STACKED,
            compactNamePlacement(WidgetKind.LOCK, 110, WIDGET_COMPACT_TALL_BUCKET_DP),
        )
        assertEquals(
            CompactName.STACKED,
            compactNamePlacement(WidgetKind.ALARM, 180, WIDGET_COMPACT_TALL_BUCKET_DP),
        )
    }

    @Test
    fun `narrow and one row high, it spends the line on state and time — not its name`() {
        // The one placement with genuinely nowhere to put a name. The name is recoverable from
        // where the widget sits; the timestamp is what stops a frame left on the home screen from
        // quietly lying, so it is never what gets cut.
        val short = WIDGET_COMPACT_TALL_BUCKET_DP - 1
        assertEquals(
            CompactName.HIDDEN,
            compactNamePlacement(WidgetKind.LOCK, WIDGET_NAME_MIN_WIDTH_DP - 1, short),
        )
        assertEquals(CompactName.HIDDEN, compactNamePlacement(WidgetKind.ALARM, 110, short))
        // A light has no such duty at any size, and "which lamp?" is the only question worth
        // answering — a lamp shown wrong is a cosmetic error, not a security one.
        assertEquals(CompactName.INLINE, compactNamePlacement(WidgetKind.LIGHT, 110, short))
    }

    // ── The picker's candidate list ───────────────────────────────────────────────────────────

    @Test
    fun `the light picker offers lights, not the camera switches that outnumber them`() {
        // The bug this fixes: `switch.*` on this house is overwhelmingly ring-mqtt camera
        // plumbing, so including that domain buried a dozen real lights under dozens of
        // live-stream and motion-detection toggles.
        val candidates = widgetCandidates(
            WidgetKind.LIGHT,
            listOf(
                entity("light.back2_light_2", state = "on"),
                entity("light.back_light_2", state = "off"),
                entity("switch.back2_live_stream", state = "on"),
                entity("switch.back2_motion_detection_2", state = "on"),
                entity("switch.back2_siren", state = "off"),
                entity("switch.basement_event_stream", state = "on"),
            ),
        )
        assertEquals(listOf("light.back2_light_2", "light.back_light_2"), candidates.map { it.entityId })
    }

    @Test
    fun `housekeeping entities are filtered out of every picker`() {
        // The app's own noise rule, which the picker had been skipping.
        val candidates = widgetCandidates(
            WidgetKind.LOCK,
            listOf(
                entity("lock.front_door_lock", state = "locked"),
                entity("lock.back_door_info", state = "unknown"),
            ),
        )
        assertEquals(listOf("lock.front_door_lock"), candidates.map { it.entityId })
    }

    @Test
    fun `the registry demotes diagnostic entities when the picker has it`() {
        // The config screen runs in the app, so it can hand over HA's entity_category map — which
        // catches the config/diagnostic entities the suffix denylist has no way to know about.
        val entities = listOf(
            entity("light.kitchen", state = "on"),
            entity("light.kitchen_calibration", state = "on"),
        )
        val categories = mapOf("light.kitchen_calibration" to "config")
        assertEquals(
            listOf("light.kitchen"),
            widgetCandidates(WidgetKind.LIGHT, entities, categories = categories).map { it.entityId },
        )
    }

    @Test
    fun `the registry collapses the Ring and ring-mqtt twins`() {
        // The household runs both integrations, so one physical light lands twice under one name.
        // ring-mqtt is this app's backend, so the `ring` twin is the one that goes.
        val entities = listOf(
            entity("light.front_light", friendlyName = "Front Light", state = "on"),
            entity("light.front_light_2", friendlyName = "Front Light", state = "on"),
        )
        val platforms = mapOf(
            "light.front_light" to RING_PLATFORM,
            "light.front_light_2" to MQTT_PLATFORM,
        )
        assertEquals(
            listOf("light.front_light_2"),
            widgetCandidates(WidgetKind.LIGHT, entities, platforms = platforms).map { it.entityId },
        )
    }

    @Test
    fun `without the registry the list still works, just less well`() {
        // Off the tailnet the picker falls back to REST, which carries no registry. Both filters
        // have to degrade to a no-op rather than emptying the list.
        val entities = listOf(
            entity("light.front_light", friendlyName = "Front Light", state = "on"),
            entity("light.front_light_2", friendlyName = "Front Light", state = "on"),
            entity("light.kitchen_calibration", state = "on"),
        )
        assertEquals(3, widgetCandidates(WidgetKind.LIGHT, entities).size)
    }

    @Test
    fun `an unreachable entity is not offered`() {
        // Nothing worth pinning to a home screen; it would render "Unavailable" forever.
        val candidates = widgetCandidates(
            WidgetKind.ALARM,
            listOf(
                entity("alarm_control_panel.home", state = "disarmed"),
                entity("alarm_control_panel.spare", state = "unavailable"),
            ),
        )
        assertEquals(listOf("alarm_control_panel.home"), candidates.map { it.entityId })
    }

    // ── Temperature widget ────────────────────────────────────────────────────────────────────

    @Test
    fun `bands split on the three thresholds`() {
        assertEquals(TempBand.COLD, temperatureBand(64.0, 68.0, 72.0, 77.0))
        assertEquals(TempBand.GOOD, temperatureBand(70.0, 68.0, 72.0, 77.0))
        assertEquals(TempBand.WARM, temperatureBand(74.8, 68.0, 72.0, 77.0))
        assertEquals(TempBand.HOT, temperatureBand(78.0, 68.0, 72.0, 77.0))
    }

    // Each boundary belongs to the CALMER band: a sensor parked exactly on 77 must
    // not flicker between orange and red.
    @Test
    fun `every boundary belongs to the calmer band`() {
        assertEquals(TempBand.GOOD, temperatureBand(68.0, 68.0, 72.0, 77.0))
        assertEquals(TempBand.GOOD, temperatureBand(72.0, 68.0, 72.0, 77.0))
        assertEquals(TempBand.WARM, temperatureBand(77.0, 68.0, 72.0, 77.0))
        assertEquals(TempBand.COLD, temperatureBand(67.9, 68.0, 72.0, 77.0))
        assertEquals(TempBand.WARM, temperatureBand(72.1, 68.0, 72.0, 77.0))
        assertEquals(TempBand.HOT, temperatureBand(77.1, 68.0, 72.0, 77.0))
    }

    // Three fields are three chances to fat-finger the order.
    @Test
    fun `thresholds entered out of order still describe a usable ladder`() {
        // Same three numbers, scrambled — the ladder must come out identical.
        assertEquals(TempBand.COLD, temperatureBand(60.0, 77.0, 68.0, 72.0))
        assertEquals(TempBand.GOOD, temperatureBand(70.0, 77.0, 68.0, 72.0))
        assertEquals(TempBand.WARM, temperatureBand(75.0, 72.0, 77.0, 68.0))
        assertEquals(TempBand.HOT, temperatureBand(80.0, 72.0, 77.0, 68.0))
    }

    @Test
    fun `equal thresholds leave a single comfortable point rather than dividing by zero`() {
        assertEquals(TempBand.GOOD, temperatureBand(70.0, 70.0, 70.0, 70.0))
        assertEquals(TempBand.COLD, temperatureBand(69.9, 70.0, 70.0, 70.0))
        assertEquals(TempBand.HOT, temperatureBand(70.1, 70.0, 70.0, 70.0))
    }

    // Colour is never the only signal — colour blindness, and a glance in bright sun.
    @Test
    fun `each band has its own words`() {
        assertEquals(
            listOf("Cold", "Comfortable", "Warm", "Hot"),
            TempBand.entries.map { temperatureLabel(it) },
        )
    }

    // PULSE's four channels are blue/violet/orange/green — none of them is red, so
    // the hot band deliberately has NO channel and renders in the error colour.
    @Test
    fun `hot has no channel because PULSE has no red one`() {
        assertEquals(Channel.EFFORT, temperatureChannel(TempBand.COLD))
        assertEquals(Channel.RECOVERY, temperatureChannel(TempBand.GOOD))
        assertEquals(Channel.STREAK, temperatureChannel(TempBand.WARM))
        assertNull(temperatureChannel(TempBand.HOT))

        assertTrue(temperatureIsAlert(TempBand.HOT))
        assertFalse(temperatureIsAlert(TempBand.WARM))
        assertFalse(temperatureIsAlert(TempBand.GOOD))
        assertFalse(temperatureIsAlert(TempBand.COLD))
    }

    @Test
    fun `the reading is trimmed to one decimal and never shows a bare point zero`() {
        assertEquals("74.8", temperatureDisplay("74.8"))
        assertEquals("74", temperatureDisplay("74.0"))
        assertEquals("74", temperatureDisplay("74"))
        assertEquals("74.8", temperatureDisplay("74.83"))
        assertEquals("-3.5", temperatureDisplay("-3.5"))
    }

    // HA sends "unknown"/"unavailable" as ordinary states; printing them as a
    // temperature would be worse than saying nothing.
    @Test
    fun `a non-numeric reading has no display value`() {
        assertNull(temperatureDisplay("unknown"))
        assertNull(temperatureDisplay("unavailable"))
        assertNull(temperatureDisplay(""))
        assertNull(temperatureDisplay(null))
    }

    // `sensor` is a huge domain — without the device_class filter the picker offers
    // every battery level, humidity reading and fps counter in the house.
    @Test
    fun `the temperature picker offers thermometers only`() {
        val candidates = widgetCandidates(
            WidgetKind.TEMPERATURE,
            listOf(
                entity(
                    "sensor.nursery_temperature_humidity_xs_sensor_air_temperature",
                    state = "74.8", deviceClass = "temperature", unit = "°F",
                ),
                entity(
                    "sensor.nursery_temperature_humidity_xs_sensor_humidity",
                    state = "53.0", deviceClass = "humidity", unit = "%",
                ),
                entity(
                    "sensor.nursery_temperature_humidity_xs_sensor_battery_level",
                    state = "100.0", deviceClass = "battery", unit = "%",
                ),
                entity("sensor.big_room_camera_fps", state = "5"),
            ),
        )
        assertEquals(
            listOf("sensor.nursery_temperature_humidity_xs_sensor_air_temperature"),
            candidates.map { it.entityId },
        )
    }

    // ── The room, and what the widget calls itself ────────────────────────────────────────────

    @Test
    fun `the room wins over the sensor's own name`() {
        // The whole point of the change. The shipped widget titled itself
        // "Temperature Humidity XS Sensor …" — a model number, ellipsised — because a sensor is
        // named for what it IS, unlike a lamp or a lock which are named for where they are.
        assertEquals(
            "Nursery",
            temperatureTitle("Nursery", "Temperature Humidity XS Sensor Air Temperature"),
        )
    }

    @Test
    fun `falls back to the sensor name when HA has no area`() {
        // Plenty of HA installs never assign areas. A wrong room would be worse than an ugly one.
        assertEquals("Attic Probe", temperatureTitle(null, "Attic Probe"))
        assertEquals("Attic Probe", temperatureTitle("   ", "Attic Probe"))
    }

    @Test
    fun `falls back again rather than rendering an empty title`() {
        assertEquals("Temperature", temperatureTitle(null, null))
        assertEquals("Temperature", temperatureTitle("", " "))
    }

    @Test
    fun `the view titles itself with the room`() {
        val view = temperatureWidgetView(
            snapshot = snapshot(
                "72.4",
                entityId = "sensor.nursery_temperature_humidity_xs_sensor_air_temperature",
                name = "Temperature Humidity XS Sensor Air Temperature",
                attributes = buildJsonObject { put("unit_of_measurement", "°F") },
            ),
            nowMs = now,
            room = "Nursery",
        )
        assertEquals("Nursery", view.name)
        assertEquals("72.4", view.reading)
        assertEquals("Warm", view.label)
    }

    @Test
    fun `a widget configured before rooms existed still titles itself`() {
        // Stored prefs from an older install have no ROOM key, so `room` arrives null.
        val view = temperatureWidgetView(
            snapshot = snapshot("70.0", name = "Attic Probe"),
            nowMs = now,
            room = null,
        )
        assertEquals("Attic Probe", view.name)
    }

    @Test
    fun `a temperature widget keeps its room on screen even when narrow`() {
        // A room temperature with no room is not a smaller widget, it is a useless one — so
        // temperature follows LIGHT rather than the security widgets, which hide the name to
        // protect the state line.
        assertEquals(
            CompactName.INLINE,
            compactNamePlacement(WidgetKind.TEMPERATURE, 110, WIDGET_COMPACT_BUCKET_DP),
        )
    }

    // ── The switch paddle ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an on-off switch reported as dimmable still offers no level`() {
        // The device this widget exists for. Z-Wave models the Inovelli VZW30-SN as a Multilevel
        // Switch, so HA hands out `supported_color_modes: ["brightness"]` for hardware that has
        // no dimmer, and the light widget believes it. The switch view must not.
        val dimmableLooking = snapshot(
            "on",
            entityId = "light.nursery_on_off_switch",
            name = "Nursery",
            attributes = buildJsonObject {
                putJsonArray("supported_color_modes") { add("brightness") }
                put("brightness", 178)
            },
        )
        // Same reading through both views: the light finds a level, the switch never looks.
        assertTrue(lightWidgetView(dimmableLooking, now).dimmable)
        assertEquals("On", switchWidgetView(dimmableLooking, now).stateLabel)
    }

    @Test
    fun `a switch that is off says so, and is still tappable`() {
        val view = switchWidgetView(snapshot("off", entityId = "switch.lamp", name = "Lamp"), now)
        assertFalse(view.on)
        assertEquals("Off", view.stateLabel)
        assertTrue(view.controllable)
    }

    @Test
    fun `an unavailable switch is drawn, but neither half can be pressed`() {
        // Drawn rather than hidden: the paddle staying put with its labels greyed is a truer
        // answer than a widget that empties itself.
        val view = switchWidgetView(snapshot("unavailable", entityId = "switch.lamp"), now)
        assertFalse(view.controllable)
        assertEquals("Unavailable", view.stateLabel)
    }

    @Test
    fun `a switch with no reading yet is dark rather than guessing off`() {
        val view = switchWidgetView(snapshot = null, nowMs = now)
        assertFalse(view.on)
        assertFalse(view.controllable)
        assertEquals("Checking…", view.stateLabel)
    }

    @Test
    fun `a switch keeps an old reading, with its age attached`() {
        // Same bargain as the light: never drop the state, always date it.
        val view = switchWidgetView(snapshot("on", ageMs = 40 * 60_000, entityId = "switch.lamp"), now)
        assertTrue(view.on)
        assertEquals("as of 40m ago", view.staleness)
    }

    @Test
    fun `a switch shows both labels at every width`() {
        // "On" and "Off" are already as short as words get, so unlike the security widgets there
        // is never a reason to trade the name away for the state line. Two size buckets, not four.
        assertEquals(
            CompactName.INLINE,
            compactNamePlacement(WidgetKind.SWITCH, 110, WIDGET_COMPACT_BUCKET_DP),
        )
    }

    @Test
    fun `a switch settles the moment it changes state, like a light`() {
        assertFalse(widgetEchoSettled(WidgetKind.SWITCH, before = "on", current = "on"))
        assertTrue(widgetEchoSettled(WidgetKind.SWITCH, before = "on", current = "off"))
    }

    @Test
    fun `the switch picker offers relays from both domains, and camera plumbing from neither`() {
        // `switch.*` was taken away from the light picker because ring-mqtt buries real switches
        // under camera toggles. The paddle needs the domain back — a plain relay lives there —
        // so it is filtered by integration instead, which drops the whole source rather than
        // guessing at names.
        val candidates = widgetCandidates(
            WidgetKind.SWITCH,
            listOf(
                entity("light.nursery_on_off_switch", state = "on"),
                entity("switch.garage_freezer", state = "on"),
                entity("switch.back2_live_stream", state = "on"),
                entity("switch.basement_motion_detection", state = "on"),
            ),
            platforms = mapOf(
                "light.nursery_on_off_switch" to "zwave_js",
                "switch.garage_freezer" to "zwave_js",
                "switch.back2_live_stream" to MQTT_PLATFORM,
                "switch.basement_motion_detection" to RING_PLATFORM,
            ),
        )
        assertEquals(
            listOf("light.nursery_on_off_switch", "switch.garage_freezer"),
            candidates.map { it.entityId },
        )
    }

    @Test
    fun `the switch picker still finds the real relays with no registry to filter by`() {
        // Off the tailnet there is no entity registry and so no platforms, and the picker falls
        // back to the suffix denylist alone: it still catches `_live_stream`, misses
        // `_motion_detection`, and — the part that matters — keeps every real relay. A worse list,
        // never a wrong one; an empty picker would read as broken.
        val candidates = widgetCandidates(
            WidgetKind.SWITCH,
            listOf(
                entity("switch.garage_freezer", state = "on"),
                entity("switch.back2_live_stream", state = "on"),
                entity("switch.basement_motion_detection", state = "on"),
            ),
        )
        assertEquals(
            listOf("switch.garage_freezer", "switch.basement_motion_detection"),
            candidates.map { it.entityId },
        )
    }

    // ── Which kinds may draw ahead of HA, and which may keep a stale reading ───────────────────

    @Test
    fun `only the security widgets wait for Home Assistant to confirm`() {
        assertTrue(widgetIsOptimistic(WidgetKind.LIGHT))
        assertTrue(widgetIsOptimistic(WidgetKind.SWITCH))
        assertFalse(widgetIsOptimistic(WidgetKind.LOCK))
        assertFalse(widgetIsOptimistic(WidgetKind.ALARM))
    }

    @Test
    fun `a temperature no longer masks itself when Home Assistant goes away`() {
        // The bug this predicate replaces: the repository asked `kind != LIGHT` and so blanked a
        // temperature on any failed fetch, contradicting `temperatureWidgetView`, which is built
        // to show an expired reading with its age. Only a lock or an alarm may drop a reading.
        assertTrue(widgetKeepsStaleReading(WidgetKind.TEMPERATURE))
        assertTrue(widgetKeepsStaleReading(WidgetKind.LIGHT))
        assertTrue(widgetKeepsStaleReading(WidgetKind.SWITCH))
        assertFalse(widgetKeepsStaleReading(WidgetKind.LOCK))
        assertFalse(widgetKeepsStaleReading(WidgetKind.ALARM))
    }
}
