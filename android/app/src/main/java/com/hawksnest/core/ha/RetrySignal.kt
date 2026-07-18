package com.hawksnest.core.ha

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A skippable backoff wait for the reconnect loop: [awaitOrTimeout] suspends for the backoff
 * duration, but a [signal] (the Offline screen's "Retry now" button) releases it immediately.
 * Conflated so repeated taps collapse into one release; [drain] discards anything signalled
 * while a connect attempt was already in flight, so a stale tap can't skip a *future* backoff.
 * Kept as its own tiny class so the wait semantics are unit-testable without a socket.
 */
class RetrySignal {
    private val signals = Channel<Unit>(Channel.CONFLATED)

    /** Release a waiting [awaitOrTimeout] now (no-op if nothing is waiting — see [drain]). */
    fun signal() {
        signals.trySend(Unit)
    }

    /** Discard any pending signal (called right before arming a new backoff wait). */
    fun drain() {
        while (signals.tryReceive().isSuccess) { /* discard */ }
    }

    /** Wait up to [timeoutMs]; returns true if released early by [signal], false on timeout. */
    suspend fun awaitOrTimeout(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { signals.receive() } != null
}
