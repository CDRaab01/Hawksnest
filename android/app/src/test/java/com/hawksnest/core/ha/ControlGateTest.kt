package com.hawksnest.core.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control gate is the crash-safety + honest-pending layer for every user-facing control.
 * These tests pin the contract: failures become error messages (never uncaught exceptions),
 * pending tracks until the entity echoes, and a silent lock reports "didn't respond".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlGateTest {

    private fun entity(id: String, state: String) = HassEntity(entityId = id, state = state)

    private fun gate(state: HaState, scope: CoroutineScope) = ControlGate(state, scope)

    @Test
    fun `a failing call surfaces an error and clears pending — never throws`() = runTest {
        val state = HaState()
        val gate = gate(state, this)
        state.setEntities(mapOf("lock.front" to entity("lock.front", "locked")))

        var error: String? = null
        val collector = launch { error = gate.errors.first() }
        runCurrent() // subscribe before the emit — the errors flow deliberately has no replay
        gate.run("lock.front", "Front Door", awaitEcho = true) {
            throw IllegalStateException("Not connected to Home Assistant.")
        }
        runCurrent()

        assertEquals("Couldn't reach Front Door — not connected.", error)
        assertTrue(gate.pending.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `pending is set during the call and cleared by the entity echo`() = runTest {
        val state = HaState()
        state.setEntities(mapOf("lock.front" to entity("lock.front", "locked")))
        val gate = gate(state, this)

        val run = launch {
            gate.run("lock.front", "Front Door", awaitEcho = true) { /* accepted */ }
        }
        runCurrent()
        assertEquals(setOf("lock.front"), gate.pending.value)

        // HA reacts — the transitional state is the echo that hands pending over to the entity.
        state.setEntities(mapOf("lock.front" to entity("lock.front", "unlocking")))
        runCurrent()
        assertTrue(gate.pending.value.isEmpty())
        run.join()
    }

    @Test
    fun `an attribute-only echo also clears pending`() = runTest {
        val state = HaState()
        val before = entity("light.porch", "on")
        state.setEntities(mapOf("light.porch" to before))
        val gate = gate(state, this)

        val run = launch { gate.run("light.porch", "Porch", awaitEcho = true) { } }
        runCurrent()
        assertEquals(setOf("light.porch"), gate.pending.value)

        // Same state string, different attributes (a brightness echo).
        state.setEntities(
            mapOf(
                "light.porch" to before.copy(
                    attributes = kotlinx.serialization.json.JsonObject(
                        mapOf("brightness" to kotlinx.serialization.json.JsonPrimitive(128)),
                    ),
                ),
            ),
        )
        runCurrent()
        assertTrue(gate.pending.value.isEmpty())
        run.join()
    }

    @Test
    fun `no echo within the timeout reports didn't respond`() = runTest {
        val state = HaState()
        state.setEntities(mapOf("lock.front" to entity("lock.front", "locked")))
        val gate = gate(state, this)

        var error: String? = null
        val collector = launch { error = gate.errors.first() }
        val run = launch { gate.run("lock.front", "Front Door", awaitEcho = true) { } }
        runCurrent()
        assertEquals(setOf("lock.front"), gate.pending.value)

        advanceTimeBy(ControlGate.ECHO_TIMEOUT_MS + 1)
        runCurrent()

        assertTrue(gate.pending.value.isEmpty())
        assertEquals("Front Door didn't respond.", error)
        run.join()
        collector.cancel()
    }

    @Test
    fun `awaitEcho=false clears pending as soon as the call is accepted`() = runTest {
        val state = HaState()
        state.setEntities(mapOf("automation.x" to entity("automation.x", "on")))
        val gate = gate(state, this)

        gate.run("automation.x", "Morning lights", awaitEcho = false) { }
        runCurrent()
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun `auth rejection gets its own message`() = runTest {
        val state = HaState()
        val gate = gate(state, this)

        var error: String? = null
        val collector = launch { error = gate.errors.first() }
        runCurrent() // subscribe before the emit — the errors flow deliberately has no replay
        gate.run("lock.front", "Front Door", awaitEcho = true) {
            throw HaAuthException("bad token")
        }
        runCurrent()

        assertEquals("Home Assistant rejected the access token.", error)
        collector.cancel()
    }
}
