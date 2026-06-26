package com.hawksnest.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * A mono data readout — the only way numerals should reach the screen. Defaults to the stat-tile
 * size; pass any style from `HawksnestTheme.dataType`.
 */
@Composable
fun DataText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HawksnestTheme.dataType.dataSmall,
    color: Color = Color.Unspecified,
) {
    Text(text = text, modifier = modifier, style = style, color = color)
}

/**
 * A readout that rolls up to [target] when it first appears (and sweeps between values on
 * change), on the slower data easing so the number feels measured rather than printed.
 */
@Composable
fun TickerNumber(
    target: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = HawksnestTheme.dataType.dataSmall,
    color: Color = Color.Unspecified,
    prefix: String = "",
    suffix: String = "",
) {
    var goal by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { goal = target }
    val value by animateIntAsState(
        targetValue = goal,
        animationSpec = PulseMotion.data(),
        label = "ticker",
    )
    DataText(text = "$prefix$value$suffix", modifier = modifier, style = style, color = color)
}
