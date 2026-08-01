package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scene pad's rules. This widget copies a specific piece of hardware, so most of what is worth
 * testing is "does it tell the truth about a plate it can only partly see" — it reads one of its
 * two entities, and the four presets are configuration rather than anything HA reports.
 */
class ScenePadModelTest {

    private val now = 1_700_000_000_000L

    private fun snapshot(state: String, ageMs: Long = 0, name: String = "Cooper's Bedroom Preset") =
        WidgetSnapshot(
            entityId = "select.bedroom_preset",
            name = name,
            state = state,
            attributes = JsonObject(emptyMap()),
            fetchedAtMs = now - ageMs,
        )

    private val presets = mapOf(
        ScenePadKey.ONE to "Starry Night",
        ScenePadKey.TWO to "Campfire",
        ScenePadKey.THREE to "Ocean",
        ScenePadKey.FOUR to "Nebula",
    )

    private fun view(
        snapshot: WidgetSnapshot?,
        presets: Map<ScenePadKey, String> = this.presets,
        leds: Map<ScenePadKey, LedColor> = ZEN32_DEFAULT_LEDS,
        relayEntityId: String? = "switch.scene_controller",
        room: String? = "Cooper's Bedroom",
    ) = scenePadView(snapshot, presets, leds, relayEntityId, room, now)

    // ── The plate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the keys come back in faceplate order, relay first and then left to right`() {
        // Verified against the hardware on 2026-08-01: the relay is the LARGE key at the TOP and
        // one to four are the 2x2 grid beneath it. A layout built on the opposite assumption would
        // be upside down, and nothing but this ordering would catch it.
        assertEquals(listOf(ScenePadKey.ONE, ScenePadKey.TWO, ScenePadKey.THREE, ScenePadKey.FOUR), SCENE_PAD_KEYS)
        assertEquals(ScenePadKey.RELAY, ScenePadKey.entries.first())
        assertEquals(SCENE_PAD_KEYS, view(snapshot("Ocean")).keys.map { it.key })
    }

    @Test
    fun `the key whose preset is playing is the only one lit`() {
        val keys = view(snapshot("Ocean")).keys
        assertEquals(listOf(false, false, true, false), keys.map { it.active })
        assertEquals("Ocean", keys.single { it.active }.preset)
    }

    @Test
    fun `a pad with no reading yet shows four dark keys, not four wrong ones`() {
        // The alternative — assuming the first key — would light the wrong lamp on every cold
        // start, which is worse than an honest blank plate.
        val view = view(snapshot = null)
        assertTrue(view.keys.none { it.active })
        assertEquals("Checking…", view.presetLabel)
    }

    @Test
    fun `an unavailable selector lights nothing and says so`() {
        val view = view(snapshot("unavailable"))
        assertTrue(view.keys.none { it.active })
        assertEquals("Unavailable", view.presetLabel)
    }

    @Test
    fun `a preset the plate does not have simply lights no key`() {
        // Changing the scene from the app, or from WLED itself, puts the select somewhere none of
        // the four keys point. The header still names it; the plate just shows nothing engaged.
        val view = view(snapshot("Rocket"))
        assertTrue(view.keys.none { it.active })
        assertEquals("Rocket", view.presetLabel)
    }

    @Test
    fun `an empty key slot is drawn but cannot be pressed`() {
        // A pad configured with three presets keeps its fourth key: five keys is what the plate
        // has, and one that vanished would change the layout under the owner's thumb.
        val view = view(snapshot("Ocean"), presets = presets - ScenePadKey.FOUR)
        val fourth = view.keys.single { it.key == ScenePadKey.FOUR }
        assertNull(fourth.preset)
        assertFalse(fourth.enabled)
        assertTrue(view.keys.first { it.key == ScenePadKey.ONE }.enabled)
    }

    @Test
    fun `the key already playing stays pressable`() {
        // Pressing the live key on the wall re-selects the preset and WLED restarts the effect.
        // The widget must not "helpfully" disable it.
        assertTrue(view(snapshot("Ocean")).keys.single { it.active }.enabled)
    }

    @Test
    fun `a pad with no relay configured draws the big key dead rather than lying`() {
        assertFalse(view(snapshot("Ocean"), relayEntityId = null).relayEnabled)
        assertFalse(view(snapshot("Ocean"), relayEntityId = "   ").relayEnabled)
        assertTrue(view(snapshot("Ocean")).relayEnabled)
    }

    // ── LEDs ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the default LEDs are the ones measured off the switch`() {
        // Read from Z-Wave configuration parameters 6-10 on 2026-08-01, not from the manual.
        assertEquals(LedColor.GREEN, ZEN32_DEFAULT_LEDS.getValue(ScenePadKey.RELAY))
        assertEquals(LedColor.YELLOW, ZEN32_DEFAULT_LEDS.getValue(ScenePadKey.ONE))
        assertEquals(LedColor.RED, ZEN32_DEFAULT_LEDS.getValue(ScenePadKey.TWO))
        assertEquals(LedColor.BLUE, ZEN32_DEFAULT_LEDS.getValue(ScenePadKey.THREE))
        assertEquals(LedColor.MAGENTA, ZEN32_DEFAULT_LEDS.getValue(ScenePadKey.FOUR))
    }

    @Test
    fun `a key with no colour stored falls back on its own default, not on the whole set`() {
        // Same rule as the temperature thresholds: a widget stored before a key gained a colour
        // keeps the colours it does have rather than being reset to five defaults.
        val partial = mapOf(ScenePadKey.ONE to LedColor.CYAN)
        val view = view(snapshot("Ocean"), leds = partial)
        assertEquals(LedColor.CYAN, view.keys.first { it.key == ScenePadKey.ONE }.led)
        assertEquals(LedColor.RED, view.keys.first { it.key == ScenePadKey.TWO }.led)
        assertEquals(LedColor.GREEN, view.relayLed)
    }

    @Test
    fun `the colour list is the device's own seven, in the device's own order`() {
        // Read off configuration parameter 6's metadata rather than transcribed, because getting a
        // Z-Wave colour map from memory is exactly how the Inovelli switch got blanked.
        assertEquals(
            listOf("WHITE", "BLUE", "GREEN", "RED", "MAGENTA", "YELLOW", "CYAN"),
            LedColor.entries.map { it.name },
        )
    }

    // ── Naming, staleness, sizing ─────────────────────────────────────────────────────────────

    @Test
    fun `the pad is named for the room, not for the preset selector`() {
        // "Cooper's Bedroom Preset" describes the plumbing. The plate is on a wall in a room.
        assertEquals("Cooper's Bedroom", view(snapshot("Ocean")).name)
    }

    @Test
    fun `a pad in no HA area falls back to the entity's own name`() {
        assertEquals(
            "Cooper's Bedroom Preset",
            view(snapshot("Ocean"), room = null).name,
        )
        assertEquals("Scene pad", scenePadTitle(room = null, entityName = null))
    }

    @Test
    fun `a pad keeps an old reading, with its age attached`() {
        assertEquals("as of 25m ago", view(snapshot("Ocean", ageMs = 25 * 60_000)).staleness)
    }

    @Test
    fun `the pad drops its header before it squeezes the plate`() {
        // Its own threshold, and higher than the shared one: every usable placement of a pad
        // clears sizeTier's 120dp, so that line would classify all of them as FULL and never fire.
        assertTrue(SCENE_PAD_HEADER_MIN_DP > WIDGET_FULL_MIN_HEIGHT_DP)
        assertFalse(scenePadShowsHeader(150))
        assertTrue(scenePadShowsHeader(SCENE_PAD_HEADER_MIN_DP))
        assertTrue(scenePadShowsHeader(260))
    }

    // ── How it behaves as a widget kind ───────────────────────────────────────────────────────

    @Test
    fun `pressing a key draws the new preset immediately`() {
        // Optimistic like a light: the select lands on the option in one step, and a confirming
        // read follows within the second. The header changing on tap is the whole feedback loop,
        // since the keys carry no labels.
        assertTrue(widgetIsOptimistic(WidgetKind.SCENE_PAD))
        val predicted = predictOptimistic(
            snapshot("Ocean"),
            service = "select_option",
            extra = mapOf("option" to "Campfire"),
            nowMs = now,
        )
        assertEquals("Campfire", predicted.state)
    }

    @Test
    fun `a preset prediction is never mistaken for an on-off one`() {
        // The bug this guards: `predictOptimistic` decides on/off from the service name, and
        // "select_option" is neither turn_on nor turn_off — so without the option branch a key
        // press would rewrite the pad's state to "off" and blank every LED until the next read.
        val predicted = predictOptimistic(
            snapshot("Ocean"),
            service = "select_option",
            extra = mapOf("option" to "Nebula"),
            nowMs = now,
        )
        assertEquals("Nebula", predicted.state)
        assertTrue(view(predicted).keys.single { it.active }.preset == "Nebula")
    }

    @Test
    fun `the pad picker offers preset selectors and nothing else`() {
        // The relay is the pad's second, write-only entity and is chosen on the next step — this
        // picker is looking for the entity whose state the widget draws.
        val candidates = widgetCandidates(
            WidgetKind.SCENE_PAD,
            listOf(
                entity("select.bedroom_preset", state = "Ocean"),
                entity("switch.scene_controller", state = "on"),
                entity("light.bedroom", state = "on"),
            ),
        )
        assertEquals(listOf("select.bedroom_preset"), candidates.map { it.entityId })
    }

    @Test
    fun `a pad keeps its reading when Home Assistant goes away`() {
        // Nothing here is security-critical, and a plate that blanked on a tailnet blip would
        // look broken rather than honest — the staleness note already dates it.
        assertTrue(widgetKeepsStaleReading(WidgetKind.SCENE_PAD))
    }
}
