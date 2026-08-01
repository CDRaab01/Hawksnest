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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.hawksnest.core.ha.stringListAttr
import com.hawksnest.core.logic.LedColor
import com.hawksnest.core.logic.SCENE_PAD_KEYS
import com.hawksnest.core.logic.ScenePadKey
import com.hawksnest.core.logic.WIDGET_TEMP_COLD_BELOW_DEFAULT
import com.hawksnest.core.logic.ZEN32_DEFAULT_LEDS
import com.hawksnest.core.logic.WIDGET_TEMP_HOT_ABOVE_DEFAULT
import com.hawksnest.core.logic.WIDGET_TEMP_WARM_ABOVE_DEFAULT
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.widgetCandidates
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.widget.data.HaCall
import com.hawksnest.widget.data.ScenePadConfig
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
                    if (kind == WidgetKind.SCENE_PAD && sensor != null) {
                        ScenePadScreen(
                            select = sensor,
                            name = resolveName(sensor, overrides),
                            relayCandidates = relayCandidates(),
                            onBack = { pendingSensor = null },
                            onSave = { config -> save(kind, sensor, scenePad = config) },
                        )
                    } else if (kind == WidgetKind.TEMPERATURE && sensor != null) {
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
                                // The two kinds with a second step. Both reuse `pendingSensor`
                                // as "the entity chosen on step one, waiting on step two".
                                if (kind == WidgetKind.TEMPERATURE || kind == WidgetKind.SCENE_PAD) {
                                    pendingSensor = entity
                                } else {
                                    save(kind, entity)
                                }
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
            provider.endsWith(ScenePadWidgetReceiver::class.java.simpleName) -> WidgetKind.SCENE_PAD
            else -> null
        }
    }

    /**
     * What the scene pad's relay step can offer: the same relays the switch widget's picker finds.
     *
     * Read off the live socket if there is one, and otherwise left empty rather than fetched over
     * REST — the relay is the pad's optional second entity, and a blank list on step two costs the
     * owner one setting, where blocking step two on a network round trip would cost them the whole
     * widget.
     */
    private fun relayCandidates(): List<HassEntity> {
        val ha = connectionManager.state
        val entities = ha.entities.value.values.toList().ifEmpty { return emptyList() }
        return widgetCandidates(
            kind = WidgetKind.SWITCH,
            entities = entities,
            categories = ha.entityCategories.value,
            platforms = ha.entityPlatforms.value,
        ).sortedBy { resolveName(it, overrides).lowercase() }
    }

    private fun save(
        kind: WidgetKind,
        entity: HassEntity,
        thresholds: Triple<Double, Double, Double>? = null,
        room: String? = null,
        scenePad: ScenePadConfig? = null,
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
                scenePad = scenePad,
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
                WidgetKind.SCENE_PAD -> "Choose a preset selector"
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

/**
 * The scene pad's second step: which preset each key fires, what colour its LED is, and which
 * entity the big relay key drives.
 *
 * ## Why the presets are not simply the select's options
 *
 * The tempting shortcut is to take the four keys from the entity's own `options` list. It does not
 * work: a WLED preset selector carries every preset in the device (twelve, on the household this
 * was built for), and which four the wall keys fire is decided by a Home Assistant automation the
 * widget cannot read. So the owner picks them here — from the live `options`, so the choices are
 * always real and never typed.
 *
 * Prefilled with the first four options rather than with any particular preset name, deliberately:
 * the widget layer contains no device-specific entity ids or preset names anywhere, and a default
 * that happened to be right for one household would be four wrong guesses for every other. The
 * LED colours *are* prefilled with a real plate's ([ZEN32_DEFAULT_LEDS]) — a colour is cosmetic
 * and costs nothing when it is wrong, where a preset fires the wrong scene.
 */
@Composable
private fun ScenePadScreen(
    select: HassEntity,
    name: String,
    relayCandidates: List<HassEntity>,
    onBack: () -> Unit,
    onSave: (ScenePadConfig) -> Unit,
) {
    val options = select.stringListAttr("options")
    var relay by remember { mutableStateOf<HassEntity?>(null) }
    val presets = remember {
        mutableStateMapOf<ScenePadKey, String>().apply {
            SCENE_PAD_KEYS.forEachIndexed { index, key -> options.getOrNull(index)?.let { put(key, it) } }
        }
    }
    val leds = remember { mutableStateMapOf<ScenePadKey, LedColor>().apply { putAll(ZEN32_DEFAULT_LEDS) } }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (options.isEmpty()) {
                "This entity offers no options, so the keys have nothing to fire. Go back and " +
                    "pick the selector the wall keys actually change."
            } else {
                "The big key at the top drives the relay. The four below it fire a preset each, " +
                    "top-left first — the same order as the plate."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        SettingRow(label = "Relay (big key)") {
            ChoiceMenu(
                current = relay?.let { resolveName(it, overrides) }
                    // Not an error state: a pad with no relay simply draws that key dead. Plenty
                    // of installs will want the four scenes and nothing else.
                    ?: if (relayCandidates.isEmpty()) "Unavailable offline" else "None",
                options = relayCandidates.map { resolveName(it, overrides) },
                onPick = { index -> relay = relayCandidates[index] },
            )
            LedMenu(current = leds.getValue(ScenePadKey.RELAY)) { leds[ScenePadKey.RELAY] = it }
        }

        SCENE_PAD_KEYS.forEachIndexed { index, key ->
            SettingRow(label = "Key ${index + 1}") {
                ChoiceMenu(
                    current = presets[key] ?: "None",
                    options = options,
                    onPick = { picked -> presets[key] = options[picked] },
                )
                LedMenu(current = leds.getValue(key)) { leds[key] = it }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = {
                    onSave(
                        ScenePadConfig(
                            relayEntityId = relay?.entityId,
                            presets = presets.toMap(),
                            leds = leds.toMap(),
                        )
                    )
                }
            ) { Text("Save") }
        }
    }
}

/** One labelled line of the scene-pad form: the setting on the left, its controls on the right. */
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** A plain dropdown. Reads its current value, offers a list, reports the index picked. */
@Composable
private fun ChoiceMenu(current: String, options: List<String>, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) {
            Text(current)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onPick(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The LED colour dropdown — the device's own seven, in the device's own order. */
@Composable
private fun LedMenu(current: LedColor, onPick: (LedColor) -> Unit) {
    ChoiceMenu(
        current = current.name.lowercase().replaceFirstChar { it.uppercaseChar() },
        options = LedColor.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() } },
        onPick = { index -> onPick(LedColor.entries[index]) },
    )
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
