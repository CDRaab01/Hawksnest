package com.hawksnest.core.logic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the web `CameraPlayer — battery camera lane refresh` suite at the seam Android can test
 * without composing: every emission of [footageLaneRefreshKeys] after the first is exactly one
 * re-run of the lane fetch (`CameraPlayer.kt` keys its `LaunchedEffect` on it), so the emission
 * list IS the refetch count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FootageLaneRefreshTest {
    private val front = "camera.front"

    private fun map(state: String, friendlyName: String? = null) =
        mapOf(front to entity(front, state = state, friendlyName = friendlyName))

    @Test
    fun `a state transition triggers exactly one refetch`() = runTest {
        val entities = MutableStateFlow(map("idle"))
        val keys = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            footageLaneRefreshKeys(entities, front, isBattery = true).collect { keys += it }
        }
        // The open: the key the first fetch is made under.
        assertEquals(listOf("idle"), keys)

        // The PIR woke it: one transition, one refetch.
        entities.value = map("streaming")
        assertEquals(listOf("idle", "streaming"), keys)
        // Attribute-only churn (battery %, snapshot republish) is not a transition.
        entities.value = map("streaming", friendlyName = "Front (87%)")
        assertEquals(listOf("idle", "streaming"), keys)
        // Back to sleep: the refetch that finds the new footage on disk.
        entities.value = map("idle")
        assertEquals(listOf("idle", "streaming", "idle"), keys)
    }

    @Test
    fun `a camera that does not sleep never refetches`() = runTest {
        val entities = MutableStateFlow(map("idle"))
        val keys = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            footageLaneRefreshKeys(entities, front, isBattery = false).collect { keys += it }
        }
        // A 24/7 camera's state says nothing about new footage — it is all new footage.
        entities.value = map("streaming")
        entities.value = map("idle")
        assertEquals(listOf<String?>(null), keys)
    }
}
