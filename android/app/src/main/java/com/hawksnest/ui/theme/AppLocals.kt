package com.hawksnest.ui.theme

import androidx.compose.runtime.compositionLocalOf
import com.hawksnest.util.WeightUnit
import com.hawksnest.util.DistanceUnit

val LocalWeightUnit = compositionLocalOf { WeightUnit.LBS }
val LocalDistanceUnit = compositionLocalOf { DistanceUnit.MI }

fun WeightUnit.formatWeight(lbs: Double): String =
    if (this == WeightUnit.KG) "${(lbs * 0.453592).toInt()} kg" else "${lbs.toInt()} lb"

fun WeightUnit.formatWeightNullable(lbs: Double?): String =
    if (lbs == null) "BW" else formatWeight(lbs)

/** Converts a stored lbs value into the user's display unit (no suffix). */
fun WeightUnit.toDisplay(lbs: Double): Double =
    if (this == WeightUnit.KG) lbs * 0.453592 else lbs

/** A bare, editable display-unit number for inline weight fields (trims a trailing .0). */
fun WeightUnit.fieldValue(lbs: Double): String {
    val v = toDisplay(lbs)
    return if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}

fun WeightUnit.formatWeightFieldLabel(): String =
    if (this == WeightUnit.KG) "Weight (kg)" else "Weight (lb)"

fun WeightUnit.formatWeightLabel(): String =
    if (this == WeightUnit.KG) "kg" else "lb"

fun WeightUnit.formatVolume(totalLb: Int): String =
    if (this == WeightUnit.KG) "%,d kg".format((totalLb * 0.453592).toInt())
    else "%,d lb".format(totalLb)

fun WeightUnit.parseToLbs(input: String): Double? {
    val value = input.toDoubleOrNull() ?: return null
    return if (this == WeightUnit.KG) value / 0.453592 else value
}
