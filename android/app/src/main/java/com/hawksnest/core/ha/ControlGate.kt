package com.hawksnest.core.ha

import com.hawksnest.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The safety layer every user-facing control call goes through.
 *
 * Two jobs:
 *  1. **Never crash the app.** A service call fails whenever the socket is down or drops
 *     mid-call ([HaClosedException], `IllegalStateException("Not connected…")`). Raw
 *     `viewModelScope.launch { callService(...) }` turned those into process crashes — exactly
 *     when the network was flaky. Here every failure becomes a message on [errors] (one
 *     app-level snackbar collects it) and the coroutine survives.
 *  2. **Expose an honest pending state.** The UI is non-optimistic for security domains: after a
 *     call is accepted, [pending] holds the entity id until HA *reacts* (any echo for that
 *     entity — a state or attribute change), the call fails, or [ECHO_TIMEOUT_MS] elapses
 *     ("didn't respond" on [errors]). Controls render spinners/disabled from this instead of
 *     appearing dead between tap and echo.
 *
 * Runs in the app scope so an in-flight control outlives screen navigation.
 */
@Singleton
class ControlGate @Inject constructor(
    private val state: HaState,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Entity ids with a control in flight (call sent, no echo yet). */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Human-readable control failures, for the app-level snackbar. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Run [call] crash-safely with pending tracking for [entityId]. [label] names the device in
     * failure messages ("Front Door didn't respond."). Set [awaitEcho] false for calls whose
     * success doesn't change the entity (sliders mid-drag, media transport, "run now") — pending
     * then clears as soon as HA accepts the call.
     */
    fun launch(
        entityId: String,
        label: String,
        awaitEcho: Boolean = true,
        call: suspend () -> Unit,
    ) {
        scope.launch { run(entityId, label, awaitEcho, call) }
    }

    /** [launch] without the scope hop — exposed for tests to drive completion deterministically. */
    internal suspend fun run(
        entityId: String,
        label: String,
        awaitEcho: Boolean,
        call: suspend () -> Unit,
    ) {
        val before = state.entities.value[entityId]
        _pending.value = _pending.value + entityId
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _pending.value = _pending.value - entityId
            _errors.emit(failureText(label, e))
            return
        }
        if (!awaitEcho) {
            _pending.value = _pending.value - entityId
            return
        }
        // Any echo for this entity counts as HA reacting — a transitional state ("locking",
        // "arming") takes over the pending story from here, and an attribute-only change
        // (brightness) is still a response. Comparing values (not references) tolerates the
        // store rebuilding maps on unrelated deltas.
        val echoed = withTimeoutOrNull(ECHO_TIMEOUT_MS) {
            state.entities.first { it[entityId] != null && it[entityId] != before }
        } != null
        _pending.value = _pending.value - entityId
        if (!echoed) _errors.emit("$label didn't respond.")
    }

    private fun failureText(label: String, e: Exception): String = when (e) {
        is HaAuthException -> "Home Assistant rejected the access token."
        is HaClosedException, is IllegalStateException -> "Couldn't reach $label — not connected."
        else -> "Couldn't control $label."
    }

    companion object {
        /** How long a control waits for HA to react before reporting "didn't respond". */
        const val ECHO_TIMEOUT_MS = 30_000L
    }
}
