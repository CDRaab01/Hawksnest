package com.hawksnest.core.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the honest-offline transitions in [HaState]: leaving CONNECTED stamps the
 * last-connected/stale-since clocks and immediately masks lock/alarm states (the "never render a
 * stale lock" invariant), and getting live again clears all of it. Everything is in-memory only —
 * there is deliberately no persistence to test.
 */
class HaStateTest {

    private fun entity(id: String, state: String) = HassEntity(entityId = id, state = state)

    private fun connectedState(vararg entities: HassEntity): HaState {
        val state = HaState()
        state.setEntities(entities.associateBy { it.entityId })
        state.setStatus(ConnectionStatus.CONNECTED)
        return state
    }

    @Test
    fun `leaving CONNECTED stamps lastConnected and staleSince and masks lock and alarm`() {
        val state = connectedState(
            entity("lock.front", "locked"),
            entity("alarm_control_panel.home", "armed_away"),
            entity("light.porch", "on"),
        )
        val before = System.currentTimeMillis()

        state.setStatus(ConnectionStatus.CONNECTING, "Reconnecting…")

        val lastConnected = state.lastConnectedMs.value
        val staleSince = state.staleSinceMs.value
        assertNotNull(lastConnected)
        assertNotNull(staleSince)
        assertTrue(lastConnected!! >= before)
        assertEquals(lastConnected, staleSince)
        // Security invariant: lock/alarm collapse immediately; everything else keeps last-known.
        assertEquals("unavailable", state.entities.value["lock.front"]!!.state)
        assertEquals("unavailable", state.entities.value["alarm_control_panel.home"]!!.state)
        assertEquals("on", state.entities.value["light.porch"]!!.state)
    }

    @Test
    fun `staleSince is not restamped by later disconnected transitions`() {
        val state = connectedState(entity("light.porch", "on"))
        state.setStatus(ConnectionStatus.CONNECTING)
        val first = state.staleSinceMs.value
        state.setStatus(ConnectionStatus.CONNECTING, "Reconnecting…")
        assertEquals(first, state.staleSinceMs.value)
    }

    @Test
    fun `reconnecting clears staleSince nextRetryAt and the reachability hint`() {
        val state = connectedState(entity("lock.front", "locked"))
        state.setStatus(ConnectionStatus.CONNECTING)
        state.setNextRetryAt(System.currentTimeMillis() + 5_000)
        state.setHostReachable(false)

        state.setStatus(ConnectionStatus.CONNECTED)

        assertNull(state.staleSinceMs.value)
        assertNull(state.nextRetryAtMs.value)
        assertNull(state.hostReachable.value)
        // lastConnected keeps the last-drop stamp until the next drop replaces it.
        assertNotNull(state.lastConnectedMs.value)
    }

    @Test
    fun `first-ever connect failure stamps nothing`() {
        val state = HaState() // status starts CONNECTING; never was CONNECTED
        state.setStatus(ConnectionStatus.ERROR, "Invalid access token")
        assertNull(state.lastConnectedMs.value)
        assertNull(state.staleSinceMs.value)
    }

    @Test
    fun `dropping with no entities stamps lastConnected but starts no grace window`() {
        val state = HaState()
        state.setStatus(ConnectionStatus.CONNECTED)
        state.setStatus(ConnectionStatus.CONNECTING)
        assertNotNull(state.lastConnectedMs.value)
        assertNull(state.staleSinceMs.value) // nothing stale to keep showing
    }

    @Test
    fun `switching to demo clears the grace state`() {
        val state = connectedState(entity("lock.front", "locked"))
        state.setStatus(ConnectionStatus.CONNECTING)
        state.setStatus(ConnectionStatus.DEMO)
        assertNull(state.staleSinceMs.value)
        assertNull(state.nextRetryAtMs.value)
    }
}
