package com.hawksnest.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.hawksnest.MainActivity
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.widgetCandidateDomains
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.widget.data.HaCall
import com.hawksnest.widget.data.WidgetEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The "which device?" screen the launcher shows when a widget is dropped on the home screen.
 *
 * One activity serves all three widgets; which one it is configuring comes from the provider that
 * launched it. The list is a plain `GET /api/states` — the widget layer has no registry and needs
 * none, since a name and a domain are all the picker shows.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Backing out must leave no half-configured widget behind, so the cancelled result is the
        // default from the first moment and only replaced once a device is actually chosen.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val kind = kindOf(appWidgetId)
        if (kind == null) {
            finish()
            return
        }

        setContent {
            HawksnestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PickerScreen(
                        kind = kind,
                        onPick = { entity -> save(kind, entity) },
                        onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                    )
                }
            }
        }
    }

    private fun kindOf(id: Int): WidgetKind? {
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className
        return when {
            provider == null -> null
            provider.endsWith(LightWidgetReceiver::class.java.simpleName) -> WidgetKind.LIGHT
            provider.endsWith(LockWidgetReceiver::class.java.simpleName) -> WidgetKind.LOCK
            provider.endsWith(AlarmWidgetReceiver::class.java.simpleName) -> WidgetKind.ALARM
            else -> null
        }
    }

    private fun save(kind: WidgetKind, entity: HassEntity) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            WidgetEntryPoint.get(this@WidgetConfigActivity).repository().configure(
                kind = kind,
                glanceId = glanceId,
                entityId = entity.entityId,
                name = resolveName(entity, overrides),
            )
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

private sealed interface PickerState {
    data object Loading : PickerState
    data class Ready(val entities: List<HassEntity>) : PickerState
    data class Problem(val blocker: WidgetBlocker) : PickerState
}

@Composable
private fun PickerScreen(
    kind: WidgetKind,
    onPick: (HassEntity) -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }

    LaunchedEffect(kind) {
        val domains = widgetCandidateDomains(kind)
        state = when (val result = WidgetEntryPoint.get(context).haClient().states()) {
            is HaCall.Ok -> PickerState.Ready(
                result.value
                    .filter { domainOf(it.entityId) in domains }
                    // An entity HA can't currently reach isn't something to pin to a home screen.
                    .filter { it.state != "unavailable" }
                    .sortedBy { resolveName(it, overrides).lowercase() }
            )
            is HaCall.Failed -> PickerState.Problem(result.blocker)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = when (kind) {
                WidgetKind.LIGHT -> "Choose a light"
                WidgetKind.LOCK -> "Choose a lock"
                WidgetKind.ALARM -> "Choose an alarm panel"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        when (val current = state) {
            PickerState.Loading -> Centred { CircularProgressIndicator() }

            is PickerState.Problem -> {
                val copy = blockerCopy(current.blocker)
                Centred {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(copy.headline, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when (current.blocker) {
                                WidgetBlocker.SIGNED_OUT, WidgetBlocker.UNAUTHORIZED ->
                                    "Connect Hawksnest to Home Assistant first."
                                else -> copy.detail
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenApp, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Open Hawksnest")
                        }
                    }
                }
            }

            is PickerState.Ready ->
                if (current.entities.isEmpty()) {
                    Centred {
                        Text(
                            text = "Home Assistant has no matching devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(current.entities, key = { it.entityId }) { entity ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(entity) }
                                    .padding(vertical = 14.dp),
                            ) {
                                Text(
                                    text = resolveName(entity, overrides),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = entity.entityId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
        }
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
