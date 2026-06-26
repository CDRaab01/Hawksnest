package com.hawksnest.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import com.hawksnest.core.logic.EntityOverride
import com.hawksnest.core.logic.OverrideMap

/**
 * Project-local label/icon overrides, seeded from the owner's stock-HA "before" screenshot where
 * the Schlage lock and its Z-Wave diagnostic sensors render with raw names ("Lock Current status
 * …"). These force the human labels Hawksnest guarantees. Highest-priority tier over the live
 * registry. Ported from `src/config/overrides.ts`.
 */
val overrides: OverrideMap = mapOf(
    "lock.front_door_lock" to EntityOverride(name = "Front Door", icon = Icons.Filled.Lock),
    "binary_sensor.front_door_current_status" to
        EntityOverride(name = "Front Door", icon = Icons.Filled.DoorFront),
    "binary_sensor.front_door_intrusion" to
        EntityOverride(name = "Intrusion", icon = Icons.Filled.Shield),
    "lock.back_door_lock" to EntityOverride(name = "Back Door", icon = Icons.Filled.Lock),
    "light.basement" to EntityOverride(name = "Basement Lights"),
    "camera.front_door" to EntityOverride(name = "Front Door"),
    "alarm_control_panel.home" to EntityOverride(name = "Home Alarm"),
)
