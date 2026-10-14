package com.hawksnest.widget.data

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.logic.widgetFailureIsGlobal

/**
 * One background pass's reads, shared between every widget in it.
 *
 * Two savings, both of which only exist because the pass is a batch. Widgets pointed at the same
 * entity (a lamp placed on two home-screen pages) share one request. And the first failure that
 * is about the *path* rather than the entity — tailnet down, token rejected, signed out — is
 * handed to every widget after it without asking again: with HA unreachable, nine widgets asking
 * one by one would hold a wakelock through nine consecutive timeouts to be told the same thing.
 *
 * Lives for one pass and is then dropped, so nothing here can serve a stale reading later.
 */
class WidgetBatchReader(private val read: suspend (entityId: String) -> HaCall<HassEntity>) {
    private val results = HashMap<String, HaCall<HassEntity>>()
    private var globalFailure: HaCall.Failed? = null

    suspend fun state(entityId: String): HaCall<HassEntity> {
        results[entityId]?.let { return it }
        globalFailure?.let { return it }
        val result = read(entityId)
        results[entityId] = result
        if (result is HaCall.Failed && widgetFailureIsGlobal(result.blocker)) globalFailure = result
        return result
    }
}
