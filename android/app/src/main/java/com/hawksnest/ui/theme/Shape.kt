package com.hawksnest.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * PULSE shape scale — tighter than Material defaults. Panels read as instrument bezels: 12dp for
 * cards and controls, 16dp for large surfaces, 8dp for compact fields. Depth comes from hairline
 * strokes and tone, not big radii or shadows.
 */
val HawksnestShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
