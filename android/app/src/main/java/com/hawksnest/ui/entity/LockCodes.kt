package com.hawksnest.ui.entity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.logic.FIRST_GUEST_SLOT
import com.hawksnest.core.logic.LAST_GUEST_SLOT
import com.hawksnest.core.logic.OWNER_SLOTS
import com.hawksnest.core.logic.buildGuestExpiryAutomation
import com.hawksnest.core.logic.guestAutomationPrefixFor
import com.hawksnest.core.logic.guestSlotFromId
import com.hawksnest.core.logic.isValidUserCode
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.theme.HawksnestTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/**
 * Manages the physical keypad codes on a Z-Wave lock (Z-Wave JS user-code slots),
 * NOT an app passcode. Owner slots + time-limited guest codes whose expiry is an
 * HA automation. Ported from web `LockCodes`. Reads the lock's entity id from the
 * same nav arg as [EntityDetailViewModel].
 */
@HiltViewModel
class LockCodesViewModel @Inject constructor(
    private val connection: ConnectionManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val entityId: String = savedStateHandle.get<String>("entityId").orEmpty()

    data class Guest(val slot: Int, val name: String, val automationId: String)

    val guests: StateFlow<List<Guest>> =
        connection.state.entities.map { entities ->
            val prefix = guestAutomationPrefixFor(entityId)
            entities.values
                .filter { it.entityId.startsWith("automation.") }
                .mapNotNull { e ->
                    val id = (e.attributes["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    if (!id.startsWith(prefix)) return@mapNotNull null
                    val slot = guestSlotFromId(id) ?: return@mapNotNull null
                    val alias = (e.attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val name = alias.removePrefix("Guest code expiry — ")
                        .replace(Regex(" \\(slot \\d+\\)$"), "")
                        .ifEmpty { "Slot $slot" }
                    Guest(slot, name, id)
                }
                .sortedBy { it.slot }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nextGuestSlot: StateFlow<Int?> =
        guests.map { gs ->
            val used = gs.map { it.slot }.toSet()
            (FIRST_GUEST_SLOT..LAST_GUEST_SLOT).firstOrNull { it !in used }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FIRST_GUEST_SLOT)

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()
    fun clearMsg() { _msg.value = null }

    fun setCode(slot: Int, code: String, label: String) {
        if (!isValidUserCode(code)) { _msg.value = "Enter a 4–8 digit code."; return }
        viewModelScope.launch {
            _msg.value = try {
                connection.callService(
                    "zwave_js",
                    "set_lock_usercode",
                    ServiceData(entityId = entityId, extra = mapOf("code_slot" to slot, "usercode" to code)),
                )
                "Code set for $label."
            } catch (_: Exception) {
                "Couldn't reach the lock."
            }
        }
    }

    fun clearCode(slot: Int, label: String) {
        viewModelScope.launch {
            _msg.value = try {
                connection.callService(
                    "zwave_js",
                    "clear_lock_usercode",
                    ServiceData(entityId = entityId, extra = mapOf("code_slot" to slot)),
                )
                "Code cleared for $label."
            } catch (_: Exception) {
                "Couldn't reach the lock."
            }
        }
    }

    fun addGuest(name: String, code: String, expiryLocal: String, slot: Int) {
        if (name.isBlank()) { _msg.value = "Enter a name."; return }
        if (!isValidUserCode(code)) { _msg.value = "Enter a 4–8 digit code."; return }
        val automation = buildGuestExpiryAutomation(entityId, slot, name.trim(), expiryLocal)
        if (automation == null) { _msg.value = "Use expiry format YYYY-MM-DDTHH:MM."; return }
        viewModelScope.launch {
            _msg.value = try {
                connection.callService(
                    "zwave_js",
                    "set_lock_usercode",
                    ServiceData(entityId = entityId, extra = mapOf("code_slot" to slot, "usercode" to code)),
                )
                connection.saveAutomationConfig(automation)
                "Guest \"${name.trim()}\" added to slot $slot."
            } catch (_: Exception) {
                "Couldn't add the guest (needs an admin HA token)."
            }
        }
    }

    fun revokeGuest(slot: Int, automationId: String) {
        viewModelScope.launch {
            _msg.value = try {
                connection.callService(
                    "zwave_js",
                    "clear_lock_usercode",
                    ServiceData(entityId = entityId, extra = mapOf("code_slot" to slot)),
                )
                connection.deleteAutomationConfig(automationId)
                "Guest revoked."
            } catch (_: Exception) {
                "Couldn't revoke the guest."
            }
        }
    }
}

@Composable
fun LockCodes(viewModel: LockCodesViewModel = hiltViewModel()) {
    val guests by viewModel.guests.collectAsState()
    val nextSlot by viewModel.nextGuestSlot.collectAsState()
    val msg by viewModel.msg.collectAsState()

    LaunchedEffect(msg) {
        if (msg != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearMsg()
        }
    }

    PanelCard {
        OWNER_SLOTS.forEach { s ->
            OwnerSlotRow(s.slot, s.label, onSet = { viewModel.setCode(s.slot, it, s.label) }, onClear = { viewModel.clearCode(s.slot, s.label) })
        }
        guests.forEach { g ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Slot ${g.slot} · auto-expires",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PulseButton(
                    "Revoke",
                    onClick = { viewModel.revokeGuest(g.slot, g.automationId) },
                    tonal = true,
                    compact = true,
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp),
                        )
                    },
                )
            }
        }
        AddGuest(nextSlot) { name, code, expiry -> nextSlot?.let { viewModel.addGuest(name, code, expiry, it) } }
        msg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
            )
        }
    }
}

@Composable
private fun OwnerSlotRow(slot: Int, label: String, onSet: (String) -> Unit, onClear: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("Slot $slot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() } },
            placeholder = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(110.dp),
        )
        PulseButton("Set", onClick = { onSet(code); code = "" }, tonal = true, compact = true)
        PulseButton("Clear", onClick = onClear, tonal = true, compact = true)
    }
}

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

@Composable
private fun AddGuest(nextSlot: Int?, onAdd: (String, String, String) -> Unit) {
    val today = remember { LocalDate.now() }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var year by remember { mutableIntStateOf(today.year) }
    var month by remember { mutableIntStateOf(today.monthValue) }
    var day by remember { mutableIntStateOf(today.dayOfMonth) }

    // Keep the day valid when a month/year change shortens the month (e.g. Jan 31 → Feb).
    val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
    if (day > daysInMonth) day = daysInMonth

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = HawksnestTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Text(
            if (nextSlot != null) "Add guest (slot $nextSlot)" else "No free guest slots",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() } },
            placeholder = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Expires (11:59 PM on)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
            DateDropdown(
                selectedLabel = MONTH_NAMES[month - 1],
                options = (1..12).map { it to MONTH_NAMES[it - 1] },
                onSelect = { month = it },
                modifier = Modifier.weight(1.2f),
            )
            DateDropdown(
                selectedLabel = day.toString(),
                options = (1..daysInMonth).map { it to it.toString() },
                onSelect = { day = it },
                modifier = Modifier.weight(1f),
            )
            DateDropdown(
                selectedLabel = year.toString(),
                options = (today.year..today.year + 2).map { it to it.toString() },
                onSelect = { year = it },
                modifier = Modifier.weight(1.3f),
            )
        }
        PulseButton(
            "Add guest",
            onClick = {
                val expiry = "%04d-%02d-%02dT23:59".format(year, month, day)
                onAdd(name, code, expiry)
                name = ""; code = ""
                year = today.year; month = today.monthValue; day = today.dayOfMonth
            },
            tonal = true,
            enabled = nextSlot != null,
        )
    }
}

/** A read-only field that opens a [DropdownMenu] of numeric date parts on tap. */
@Composable
private fun DateDropdown(
    selectedLabel: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HawksnestTheme.pulse.hairline, MaterialTheme.shapes.small)
                .background(HawksnestTheme.pulse.panel, MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
