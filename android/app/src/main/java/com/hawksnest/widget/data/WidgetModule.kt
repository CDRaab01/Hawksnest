package com.hawksnest.widget.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @Singleton
    abstract fun bindCredentialSource(impl: StoredCredentialSource): CredentialSource
}

/**
 * How the widget layer reaches the graph.
 *
 * A `GlanceAppWidget` and an `ActionCallback` are both constructed by the framework, not by Hilt,
 * so neither can take an injected constructor. An entry point is the supported way in — and it
 * lands on the same singletons the app uses, in the same process, so a widget action and a tap in
 * the app share one OkHttp pool and one credential store.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): WidgetRepository
    fun haClient(): WidgetHaClient
    fun json(): Json

    companion object {
        fun get(context: Context): WidgetEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
    }
}
