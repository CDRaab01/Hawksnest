package com.hawksnest.widget.data

import com.hawksnest.core.logic.WIDGET_ECHO_TIMEOUT_MS
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.widgetEchoSettled
import kotlinx.coroutines.delay

/**
 * The widget's stand-in for `ControlGate`'s echo wait.
 *
 * In-app, "did HA actually do it?" is answered by the entity stream: `ControlGate` suspends on
 * `state.entities.first { it != before }`. A widget has no stream, so it asks again — polling the
 * REST endpoint until the state settles somewhere other than where it started, or until the same
 * thirty seconds `ControlGate` allows have passed.
 *
 * The clock and the sleep are injected so the timeout, the jam and the never-responds cases are
 * ordinary unit tests rather than thirty-second ones.
 */
class WidgetEcho(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitSettled(
        kind: WidgetKind,
        before: String?,
        timeoutMs: Long = WIDGET_ECHO_TIMEOUT_MS,
        intervalMs: Long = POLL_INTERVAL_MS,
        fetch: suspend () -> String?,
    ): EchoOutcome {
        val deadline = nowMs() + timeoutMs
        var last: String? = null
        while (true) {
            sleep(intervalMs)
            val current = fetch()
            if (current != null) {
                last = current
                if (widgetEchoSettled(kind, before, current)) return EchoOutcome.Settled(current)
            }
            if (nowMs() >= deadline) return EchoOutcome.GaveUp(last)
        }
    }

    companion object {
        /** Fast enough to feel responsive, slow enough not to hammer HA through the tunnel. */
        const val POLL_INTERVAL_MS = 1_000L
    }
}

sealed interface EchoOutcome {
    /** HA reached a settled state — the command landed (or the lock jammed, which is also an answer). */
    data class Settled(val state: String) : EchoOutcome

    /** Nothing settled in time. [lastState] is the last thing we did see, if anything. */
    data class GaveUp(val lastState: String?) : EchoOutcome
}
