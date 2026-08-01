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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
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
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.WIDGET_TEMP_COLD_BELOW_DEFAULT
import com.hawksnest.core.logic.WIDGET_TEMP_HOT_ABOVE_DEFAULT
import com.hawksnest.core.logic.WIDGET_TEMP_WARM_ABOVE_DEFAULT
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.widgetCandidates
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.widget.data.HaCall
import com.hawksnest.widget.data.WidgetEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The "which device?" screen the launcher shows when a widget is dropped on the home screen.
 *
 * One activity serves all three widgets; which one it is configuring comes from the provider that
 * launched it. The list is a plain `GET /api/states` — the widget layer has no registry and needs
 * none, since a name and a domain are all the picker shows.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    /**
     * Unlike the widgets themselves, this screen is an ordinary activity in the app process — so
     * it can use the app's own connection rather than the widgets' REST path, and with it HA's
     * entity registry. That registry is what lets the list hide diagnostic entities and collapse
     * the Ring/ring-mqtt twins, neither of which REST can see.
     */
    @Inject lateinit var connectionManager: ConnectionManager

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
                    // A temperature widget needs a second step: the sensor alone
                    // says nothing about what "comfortable" means in that room.
                    var pendingSensor by remember { mutableStateOf<HassEntity?>(null) }
                    val sensor = pendingSensor
                    if (kind == WidgetKind.TEMPERATURE && sensor != null) {
                        ThresholdScreen(
                            sensor = sensor,
                            name = resolveName(sensor, overrides),
                            // HA's own area for this sensor, prefilled and editable. Resolved
                            // HERE because this screen has the app's socket; the widget itself
                            // speaks REST, which cannot read the area registry at all.
                            suggestedRoom = connectionManager.state.areas.value[sensor.entityId],
                            onBack = { pendingSensor = null },
                            onSave = { cold, warm, hot, room ->
                                save(kind, sensor, Triple(cold, warm, hot), room)
                            },
                        )
                    } else {
                        PickerScreen(
                            kind = kind,
                            connectionManager = connectionManager,
                            onPick = { entity ->
                                if (kind == WidgetKind.TEMPERATURE) pendingSensor = entity
                                else save(kind, entity)
                            },
                            onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                        )
                    }
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
            provider.endsWith(TemperatureWidgetReceiver::class.java.simpleName) ->
                WidgetKind.TEMPERATURE
            provider.endsWith(SwitchWidgetReceiver::class.java.simpleName) -> WidgetKind.SWITCH
            else -> null
        }
    }

    private fun save(
        kind: WidgetKind,
        entity: HassEntity,
        thresholds: Triple<Double, Double, Double>? = null,
        room: String? = null,
    ) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            WidgetEntryPoint.get(this@WidgetConfigActivity).repository().configure(
                kind = kind,
                glanceId = glanceId,
                entityId = entity.entityId,
                name = resolveName(entity, overrides),
                thresholds = thresholds,
                // Not derived from the entity at save time: for widgets other than temperature
                // this is null, and the picker path has no room step.
                room = room ?: connectionManager.state.areas.value[entity.entityId],
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
    connectionManager: ConnectionManager,
    onPick: (HassEntity) -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }

    LaunchedEffect(kind) {
        val ha = connectionManager.state
        // The socket is already starting (HawksnestApp.onCreate), so wait a beat for it: HaSource
        // loads the registries *before* it reports CONNECTED, which means status alone is proof
        // the categories and platforms below are populated. DEMO doesn't qualify — a picker
        // offering fixture lights would be a trap.
        val live = withTimeoutOrNull(SOCKET_WAIT_MS) {
            ha.status.first { it == ConnectionStatus.CONNECTED }
            ha.entities.first { it.isNotEmpty() }
        }

        state = if (live != null) {
            PickerState.Ready(
                widgetCandidates(
                    kind = kind,
                    entities = live.values.toList(),
                    categories = ha.entityCategories.value,
                    platforms = ha.entityPlatforms.value,
                ).sortedBy { resolveName(it, overrides).lowercase() }
            )
        } else {
            // No socket in time — off the tailnet, or signed out. Fall back to the widgets' own
            // REST path, which also produces the right "signed out"/"can't reach" message.
            when (val result = WidgetEntryPoint.get(context).haClient().states()) {
                is HaCall.Ok -> PickerState.Ready(
                    widgetCandidates(kind, result.value)
                        .sortedBy { resolveName(it, overrides).lowercase() }
                )
                is HaCall.Failed -> PickerState.Problem(result.blocker)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = when (kind) {
                WidgetKind.LIGHT -> "Choose a light"
                WidgetKind.LOCK -> "Choose a lock"
                WidgetKind.ALARM -> "Choose an alarm panel"
                WidgetKind.TEMPERATURE -> "Choose a temperature sensor"
                WidgetKind.SWITCH -> "Choose a switch"
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

/**
 * Where the temperature widget's two colour thresholds are set, once a sensor is picked.
 *
 * Prefilled with the sensor's CURRENT reading in the hint and the nursery defaults in
 * the fields, because the useful question is "is this number OK?" and you can only
 * answer it if you can see the number. Values are in the sensor's own unit — the
 * widget never converts, so a °C household types °C here and everything downstream
 * works unchanged.
 */
@Composable
private fun ThresholdScreen(
    sensor: HassEntity,
    name: String,
    suggestedRoom: String?,
    onBack: () -> Unit,
    onSave: (Double, Double, Double, String) -> Unit,
) {
    val unit = sensor.stringAttr("unit_of_measurement") ?: ""
    // Prefilled from HA's area and editable, because the area registry is not always populated
    // and "which room is this" is a question the owner can always answer even when HA cannot.
    var room by remember { mutableStateOf(suggestedRoom.orEmpty()) }
    var cold by remember { mutableStateOf(WIDGET_TEMP_COLD_BELOW_DEFAULT.toDisplay()) }
    var warm by remember { mutableStateOf(WIDGET_TEMP_WARM_ABOVE_DEFAULT.toDisplay()) }
    var hot by remember { mutableStateOf(WIDGET_TEMP_HOT_ABOVE_DEFAULT.toDisplay()) }
    val coldValue = cold.trim().toDoubleOrNull()
    val warmValue = warm.trim().toDoubleOrNull()
    val hotValue = hot.trim().toDoubleOrNull()

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Reads ${sensor.state}$unit right now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = room,
            onValueChange = { room = it },
            label = { Text("Room") },
            placeholder = { Text("Nursery") },
            supportingText = {
                Text(
                    if (suggestedRoom != null) {
                        "From Home Assistant. This is what the widget will be called."
                    } else {
                        // Not an error: plenty of HA installs never assign areas. Say what it
                        // costs so the empty field reads as a choice rather than a failure.
                        "Home Assistant has no area for this sensor. Leave blank to use its " +
                            "own name instead."
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Text(
            text = "Blue below the first number, green up to the second, " +
                "orange up to the third, red above it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedTextField(
            value = cold,
            onValueChange = { cold = it },
            label = { Text("Cold below ($unit)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        OutlinedTextField(
            value = warm,
            onValueChange = { warm = it },
            label = { Text("Warm above ($unit)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = hot,
            onValueChange = { hot = it },
            label = { Text("Hot above ($unit)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(
                // All three must parse; the model sorts a set entered out of order,
                // but a blank or non-numeric field would silently fall back to a
                // default and look like the setting was ignored.
                enabled = coldValue != null && warmValue != null && hotValue != null,
                onClick = { onSave(coldValue!!, warmValue!!, hotValue!!, room) },
            ) { Text("Save") }
        }
    }
}

/** Whole numbers without a trailing ".0", so the fields read like a person wrote them. */
private fun Double.toDisplay(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

/**
 * How long the picker waits for the app's socket before falling back to REST. Long enough for a
 * connect on the tailnet, short enough that being off it doesn't feel like a hang.
 */
private const val SOCKET_WAIT_MS = 4_000L

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
