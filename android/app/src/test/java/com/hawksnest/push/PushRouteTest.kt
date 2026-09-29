package com.hawksnest.push

import com.hawksnest.core.logic.CameraStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRouteTest {

    private fun msg(
        title: String = "x",
        tags: List<String> = emptyList(),
        click: String? = null,
    ) = NtfyMessage(id = "1", title = title, body = "b", tags = tags, priority = 3, click = click, attachUrl = null)

    @Test
    fun `bell tag classifies as doorbell`() {
        assertEquals(PushKind.Doorbell, PushRoute.kindOf(msg(title = "Doorbell", tags = listOf("bell"))))
    }

    @Test
    fun `alarm tags classify as alarm`() {
        for (tag in listOf("shield", "rotating_light")) {
            assertEquals(PushKind.Alarm, PushRoute.kindOf(msg(title = "Alarm armed away", tags = listOf(tag))))
        }
    }

    @Test
    fun `title fallback classifies when tags are missing`() {
        assertEquals(PushKind.Doorbell, PushRoute.kindOf(msg(title = "Doorbell")))
        assertEquals(PushKind.Alarm, PushRoute.kindOf(msg(title = "Alarm triggered")))
    }

    @Test
    fun `unknown message is generic`() {
        assertEquals(PushKind.Generic, PushRoute.kindOf(msg(title = "Water leak")))
    }

    // `walking`/`dog`/`cat` are the tags hawksnest_push_camera_object sets.
    @Test
    fun `walking tag classifies as person`() {
        assertEquals(
            PushKind.Person,
            PushRoute.kindOf(msg(title = "Person at Kitchen", tags = listOf("walking"))),
        )
    }

    @Test
    fun `dog and cat tags classify as pet`() {
        for (tag in listOf("dog", "cat")) {
            assertEquals(
                PushKind.Pet,
                PushRoute.kindOf(msg(title = "Dog at Big Room", tags = listOf(tag))),
            )
        }
    }

    // Alarm is matched first on purpose — a message carrying both must stay an alarm.
    @Test
    fun `alarm wins over a camera tag on the same message`() {
        assertEquals(
            PushKind.Alarm,
            PushRoute.kindOf(msg(title = "Alarm triggered", tags = listOf("walking", "shield"))),
        )
    }

    // A person alert's title starts with "Person"/"Dog"/"Cat", never "alarm", and
    // contains no "doorbell" — so an untagged one must not be misrouted.
    @Test
    fun `camera titles do not collide with the doorbell or alarm fallbacks`() {
        assertEquals(PushKind.Generic, PushRoute.kindOf(msg(title = "Person at Kitchen")))
        assertEquals(PushKind.Generic, PushRoute.kindOf(msg(title = "Cat at Big Room")))
    }

    // The camera automation appends &event=<id>; the camera must still resolve, or
    // tapping a person alert would land on Home instead of the camera.
    @Test
    fun `cameraOf still works when the click url also carries an event id`() {
        val m = msg(click = "https://h:8443/?camera=camera.kitchen&event=1785450299.202571-j2epy2")
        assertEquals("camera.kitchen", PushRoute.cameraOf(m))
    }

    @Test
    fun `cameraOf pulls the logical camera id from the click url`() {
        val m = msg(click = "https://dragonfly.tail2ce561.ts.net:8443/?camera=camera.front_door")
        assertEquals("camera.front_door", PushRoute.cameraOf(m))
    }

    @Test
    fun `cameraOf handles the param mid-query and url-encoding`() {
        assertEquals(
            "camera.back_side_yard",
            PushRoute.cameraOf(msg(click = "https://h/?x=1&camera=camera.back_side_yard&y=2")),
        )
        assertEquals(
            "camera.front door",
            PushRoute.cameraOf(msg(click = "https://h/?camera=camera.front%20door")),
        )
    }

    // The camera automation appends &event=<id> so a tap lands on the moment that
    // triggered the alert rather than the live view.
    @Test
    fun `eventOf pulls the frigate event id from the click url`() {
        val m = msg(click = "https://h:8443/?camera=camera.kitchen&event=1785450299.202571-j2epy2")
        assertEquals("1785450299.202571-j2epy2", PushRoute.eventOf(m))
        // ...and the camera must still resolve from the same URL.
        assertEquals("camera.kitchen", PushRoute.cameraOf(m))
    }

    @Test
    fun `eventOf handles the param appearing first`() {
        assertEquals(
            "abc-1",
            PushRoute.eventOf(msg(click = "https://h/?event=abc-1&camera=camera.x")),
        )
    }

    // Doorbell and alarm notifications carry no event — those taps open live.
    @Test
    fun `eventOf is null when absent`() {
        assertNull(PushRoute.eventOf(msg(click = "https://h:8443/?camera=camera.front_door")))
        assertNull(PushRoute.eventOf(msg(click = null)))
    }

    // An empty value must not become an empty-string event id, which would match
    // no event and leave the player seeking nowhere.
    @Test
    fun `eventOf is null for an empty value`() {
        assertNull(PushRoute.eventOf(msg(click = "https://h/?camera=camera.x&event=")))
    }

    @Test
    fun `cameraOf is null when absent`() {
        assertNull(PushRoute.cameraOf(msg(click = null)))
        assertNull(PushRoute.cameraOf(msg(click = "https://dragonfly.ts.net:8443/")))
    }

    // --- tap-through: every tap lands on the thing the alert is about ---------------------------

    private val front = "https://dragonfly.tail2ce561.ts.net:8443"

    private fun full(title: String, body: String = "b", tags: List<String> = emptyList(), click: String? = null) =
        NtfyMessage(id = "1", title = title, body = body, tags = tags, priority = 3, click = click, attachUrl = null)

    private fun labels(m: NtfyMessage) = PushRoute.actionsFor(m).map { it.label }

    @Test
    fun `entityOf reads the web app's entity path`() {
        assertEquals("binary_sensor.garage_bay_main", PushRoute.entityOf(msg(click = "$front/entity/binary_sensor.garage_bay_main")))
        assertEquals(
            "binary_sensor.garage_bay_main",
            PushRoute.entityOf(msg(click = "$front/entity/binary_sensor.garage_bay_main?camera=camera.garage")),
        )
        assertNull(PushRoute.entityOf(msg(click = "$front/?camera=camera.garage")))
        assertNull(PushRoute.entityOf(msg(click = "$front/entity/not-an-entity")))
        assertNull(PushRoute.entityOf(msg(click = null)))
    }

    @Test
    fun `doorbell opens its camera and offers watch, talk and reply`() {
        val m = full("Doorbell", tags = listOf("bell"), click = "$front/?camera=camera.front_door_reolink")
        assertEquals(PushTarget.Camera("camera.front_door_reolink"), PushRoute.tapTarget(m))
        assertEquals(listOf("Watch", "Talk", "Reply"), labels(m))
        val talk = PushRoute.actionsFor(m)[1] as PushAction.Open
        assertEquals(CameraStart.TALK, (talk.target as PushTarget.Camera).start)
        assertEquals(PushAction.ReplyMenu("camera.front_door_reolink"), PushRoute.actionsFor(m)[2])
    }

    @Test
    fun `a Frigate person alert opens the moment, with the clip and live`() {
        val m = full("Person at Kitchen", tags = listOf("walking"), click = "$front/?camera=camera.kitchen&event=e1")
        assertEquals(PushTarget.Camera("camera.kitchen", "e1"), PushRoute.tapTarget(m))
        assertEquals(listOf("View clip", "Live"), labels(m))
    }

    @Test
    fun `a pet alert offers the clip only, and a hub motion alert offers live only`() {
        assertEquals(listOf("View clip"), labels(full("Dog at Garage", tags = listOf("dog"), click = "$front/?camera=camera.garage&event=e2")))
        assertEquals(listOf("Live"), labels(full("Person at Front", tags = listOf("walking"), click = "$front/?camera=camera.front")))
    }

    @Test
    fun `a garage alert opens the door, with its camera one button away`() {
        val m = full(
            "Garage bay still open",
            tags = listOf("warning"),
            click = "$front/entity/binary_sensor.garage_bay_main?camera=camera.garage",
        )
        assertEquals(PushTarget.Entity("binary_sensor.garage_bay_main"), PushRoute.tapTarget(m))
        assertEquals(listOf("View garage", "Garage camera"), labels(m))
    }

    @Test
    fun `an old-format garage alert still opens its camera`() {
        // What HA sends until its automations carry an entity link: nothing regresses.
        val m = full("Garage bay open", tags = listOf("warning"), click = "$front/?camera=camera.garage")
        assertEquals(PushTarget.Camera("camera.garage"), PushRoute.tapTarget(m))
        assertEquals(listOf("View camera"), labels(m))
    }

    @Test
    fun `a triggered alarm lands on Home with a banner`() {
        val m = full(
            "Alarm triggered",
            body = "Back Door opened at 10:42 PM",
            tags = listOf("rotating_light"),
            click = "$front/entity/alarm_control_panel.mfa_alarm",
        )
        assertEquals(PushTarget.Home(banner = true), PushRoute.tapTarget(m))
        assertEquals(listOf("Cameras", "View alarm"), labels(m))
    }

    @Test
    fun `a disarmed alert offers arm away, and nothing ever offers disarm or unlock`() {
        val disarmed = full("Alarm disarmed", tags = listOf("shield"), click = "$front/entity/alarm_control_panel.mfa_alarm")
        assertEquals(PushTarget.Entity("alarm_control_panel.mfa_alarm"), PushRoute.tapTarget(disarmed))
        assertEquals(listOf("View alarm", "Arm away"), labels(disarmed))
        assertEquals(PushAction.ArmAway("alarm_control_panel.mfa_alarm"), PushRoute.actionsFor(disarmed)[1])

        val armed = full("Alarm armed · Home", tags = listOf("shield"), click = "$front/entity/alarm_control_panel.mfa_alarm")
        assertEquals(listOf("View alarm"), labels(armed))

        val every = listOf(disarmed, armed, full("Alarm triggered", tags = listOf("rotating_light")))
            .flatMap { PushRoute.actionsFor(it) }
        assertTrue(every.none { it.label.contains("Disarm", ignoreCase = true) || it.label.contains("Unlock", ignoreCase = true) })
    }

    @Test
    fun `a battery alert opens the device and a watchdog alert opens the camera`() {
        val battery = full("Upstairs Motion battery low", tags = listOf("warning"), click = "$front/entity/sensor.upstairs_motion_battery")
        assertEquals(PushTarget.Entity("sensor.upstairs_motion_battery"), PushRoute.tapTarget(battery))
        assertEquals(listOf("View device"), labels(battery))

        val watchdog = full("First Floor Stairway stopped recording", tags = listOf("warning"), click = "$front/?camera=camera.first_floor_stairway")
        assertEquals(PushTarget.Camera("camera.first_floor_stairway"), PushRoute.tapTarget(watchdog))
        assertEquals(listOf("View camera"), labels(watchdog))
    }

    @Test
    fun `arm away reports what the panel read back, and counts arming as taken`() {
        assertEquals("Armed away.", PushRoute.armAwayOutcome("armed_away"))
        assertEquals("Arming away. The exit delay is running.", PushRoute.armAwayOutcome("arming"))
        assertEquals("Not armed. Home Assistant still says disarmed.", PushRoute.armAwayOutcome("disarmed"))
        assertEquals("Not armed. Home Assistant still says armed home.", PushRoute.armAwayOutcome("armed_home"))
        assertTrue(PushRoute.armAwayOutcome(null).startsWith("Sent, but"))
        assertTrue(PushRoute.armAwayTook("arming"))
        assertTrue(PushRoute.armAwayTook("armed_away"))
        assertTrue(!PushRoute.armAwayTook("disarmed") && !PushRoute.armAwayTook(null))
    }

    @Test
    fun `a quick reply says sent, not played, and points at Talk when it fails`() {
        assertEquals("Sent “We'll be right there.”", PushRoute.replyOutcome("We'll be right there.", sent = true))
        assertEquals("Couldn't play that. Open Hawksnest to try Talk.", PushRoute.replyOutcome("x", sent = false))
    }

    @Test
    fun `an alert that names nothing opens Home and carries no buttons`() {
        val m = full("Frigate watchdog", tags = listOf("warning"), click = "https://dragonfly.tail2ce561.ts.net:8447/")
        assertEquals(PushTarget.Home(), PushRoute.tapTarget(m))
        assertTrue(PushRoute.actionsFor(m).isEmpty())
    }
}
