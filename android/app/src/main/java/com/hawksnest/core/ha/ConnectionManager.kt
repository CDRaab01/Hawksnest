package com.hawksnest.core.ha

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects and drives the active data [Source] and exposes control actions — the Kotlin analogue of
 * `src/store/connection.ts`. Phase 1 always uses the demo [FixtureSource]; the live HA WebSocket
 * source lands behind the same seam (selected when credentials are saved) in a later step.
 */
@Singleton
class ConnectionManager @Inject constructor(
    val state: HaState,
    private val fixtureSource: FixtureSource,
) {
    private var current: Source? = null

    /** (Re)start the active source — call on app/screen mount. */
    suspend fun start() {
        current?.stop()
        // TODO(phase1-live): select the live HA source when a URL + token are saved.
        current = fixtureSource
        current?.start()
    }

    fun stop() {
        current?.stop()
        current = null
    }

    /** Perform a service call through the active source (non-optimistic — the echo reconciles). */
    suspend fun callService(domain: String, service: String, data: ServiceData) {
        current?.callService(domain, service, data)
    }
}
