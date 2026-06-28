package com.hawksnest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented tests driving the **real** Hawksnest app against the repo's scriptable mock
 * Home Assistant (`mock-ha/`) — the same fake backend the web Playwright suite uses. They run on a
 * KVM-accelerated emulator; the host's mock server is reached over the loopback alias `10.0.2.2`.
 *
 * Prereqs (see `android/README.md`):
 *   1. `PORT=8799 npm run mock-ha` on the host.
 *   2. A booted emulator, then `./gradlew :app:connectedDebugAndroidTest`.
 *
 * Scope is deliberately the connection + control contract: the app connecting to HA, issuing a
 * non-optimistic `call_service` (outbound), and reflecting a pushed entity state (inbound). No real
 * HA, no real lock.
 */
@RunWith(AndroidJUnit4::class)
class MockHaInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val mockUrl = "http://10.0.2.2:8799"
    private val token = "e2e-token"
    private val mock = MockControl(mockUrl)

    @Before
    fun resetScenario() {
        assertTrue(
            "mock-ha is not reachable at $mockUrl — start it with `PORT=8799 npm run mock-ha`",
            mock.health(),
        )
        mock.reset("default")
    }

    /** The app launches under instrumentation and renders its Home chrome (no network needed). */
    @Test
    fun appLaunches_showsHomeChrome() {
        compose.onNodeWithText("Hawksnest").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    /** Entering the mock's URL + a token in Settings and tapping Connect reaches CONNECTED. */
    @Test
    fun connectsToMockHa_showsConnected() {
        connectToMock()
        compose.onNodeWithText("Connected").assertIsDisplayed()
    }

    /** Tapping the "Away" arm circle issues an `alarm_arm_away` call_service the mock records. */
    @Test
    fun armAway_sendsServiceCallToMockHa() {
        connectToMock()
        Espresso.pressBack() // Settings -> Home
        waitForNode { compose.onAllNodesWithContentDescription("Away") }
        compose.onNodeWithContentDescription("Away").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            mock.calls().any { it.domain == "alarm_control_panel" && it.service == "alarm_arm_away" }
        }
        val call = mock.calls().first { it.service == "alarm_arm_away" }
        assertEquals("alarm_control_panel.home", call.entityId)
    }

    /** A state pushed over the live subscription updates the Home security read-out (inbound path). */
    @Test
    fun lockStatePush_updatesSecurityReadout() {
        connectToMock()
        Espresso.pressBack() // Settings -> Home
        waitForText("All doors locked")

        mock.pushState("lock.front_door_lock", "unlocked")

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("unlocked", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** From Home: open Settings, enter the mock URL + token, Connect, and wait for CONNECTED. */
    private fun connectToMock() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settingsUrlField").performTextClearance()
        compose.onNodeWithTag("settingsUrlField").performTextInput(mockUrl)
        compose.onNodeWithTag("settingsTokenField").performTextInput(token)
        compose.onNodeWithText("Connect").performClick()
        waitForText("Connected")
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNode(query: () -> androidx.compose.ui.test.SemanticsNodeInteractionCollection) =
        compose.waitUntil(timeoutMillis = 10_000) { query().fetchSemanticsNodes().isNotEmpty() }
}
