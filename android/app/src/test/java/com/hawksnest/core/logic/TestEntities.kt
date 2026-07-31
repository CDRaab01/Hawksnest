package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Build a [HassEntity] for tests, mirroring the web `make(...)` helper. */
fun entity(
    entityId: String,
    friendlyName: String? = null,
    icon: String? = null,
    state: String = "on",
    deviceClass: String? = null,
    unit: String? = null,
): HassEntity = HassEntity(
    entityId = entityId,
    state = state,
    attributes = buildJsonObject {
        if (friendlyName != null) put("friendly_name", friendlyName)
        if (icon != null) put("icon", icon)
        if (deviceClass != null) put("device_class", deviceClass)
        if (unit != null) put("unit_of_measurement", unit)
    },
)
