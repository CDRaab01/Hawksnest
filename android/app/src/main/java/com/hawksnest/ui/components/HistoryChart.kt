package com.hawksnest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.core.ha.HistoryPoint
import com.hawksnest.core.logic.chartSeries
import com.hawksnest.core.logic.timeAxis
import com.hawksnest.core.logic.timeFraction
import com.hawksnest.core.logic.valueAxis
import com.hawksnest.core.logic.valueFraction
import com.hawksnest.ui.theme.HawksnestTheme

/** Room under the plot for the time-axis labels. */
private val AXIS_ROW = 18.dp

/**
 * The entity-history chart: a channel line over a labelled value axis and a labelled time axis,
 * with a glow dot on the latest sample. Numeric series (sensors, climate, levels) draw a line;
 * series whose states aren't all numbers (lock/binary/cover/…) draw a step chart over discrete
 * levels, each level named on the axis.
 *
 * Axis math lives in `core/logic/Chart.kt` (the 1:1 twin of the web `lib/chart.ts`); this file is
 * placement only. **x is time, not sample index** — HA history is event-driven, so index spacing
 * would misplace every sample under a labelled clock axis.
 *
 * Distinct from [Sparkline], which stays deliberately bare for stat tiles and record rows: a chart
 * big enough to read numbers off is a chart that owes the reader its axes.
 */
@Composable
fun HistoryChart(
    points: List<HistoryPoint>,
    modifier: Modifier = Modifier,
    channel: Color = HawksnestTheme.pulse.effort,
    unit: String? = null,
    height: Dp = 96.dp,
    strokeWidth: Dp = 2.dp,
) {
    if (points.size < 2) return
    val pulse = HawksnestTheme.pulse
    val measurer = rememberTextMeasurer()
    val labelStyle: TextStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val series = remember(points) { chartSeries(points) }
    val axis = remember(series, unit) { valueAxis(series, unit) }
    val times = remember(series) { timeAxis(series.times.first(), series.times.last()) }

    Canvas(modifier.fillMaxWidth().height(height + AXIS_ROW)) {
        val gapPx = 6.dp.toPx()
        val axisRowPx = AXIS_ROW.toPx()
        val strokePx = strokeWidth.toPx()
        val dotRadius = strokePx * 1.6f

        val valueLabels = axis.ticks.map { measurer.measure(AnnotatedString(it.label), labelStyle) }
        val gutter = (valueLabels.maxOfOrNull { it.size.width } ?: 0) + gapPx
        val plotLeft = gutter
        val plotWidth = (size.width - plotLeft).coerceAtLeast(1f)
        // Inset the plot by the dot radius so the latest-sample glow isn't clipped at the edges.
        val plotTop = dotRadius
        val plotHeight = (size.height - axisRowPx - dotRadius * 2).coerceAtLeast(1f)

        val t0 = series.times.first()
        val t1 = series.times.last()
        fun px(i: Int) = plotLeft + timeFraction(series.times[i], t0, t1) * plotWidth
        fun py(v: Float) = plotTop + (1f - valueFraction(v, axis.lo, axis.hi)) * plotHeight

        // Value gridlines + their labels, right-aligned in the gutter.
        axis.ticks.forEachIndexed { i, tick ->
            val y = py(tick.value)
            drawLine(
                color = pulse.hairline,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = valueLabels[i]
            drawText(
                layout,
                topLeft = Offset(
                    x = plotLeft - gapPx - layout.size.width,
                    // coerceIn throws on an empty range, so the ceiling can never fall below 0 —
                    // a caller that asks for a very short chart gets a cramped label, not a crash.
                    y = (y - layout.size.height / 2f)
                        .coerceIn(0f, (size.height - axisRowPx - layout.size.height).coerceAtLeast(0f)),
                ),
            )
        }

        // Time gridlines + their labels, centred on the tick and clamped inside the plot.
        times.forEach { tick ->
            val x = plotLeft + timeFraction(tick.t, t0, t1) * plotWidth
            drawLine(
                color = pulse.hairline,
                start = Offset(x, plotTop),
                end = Offset(x, plotTop + plotHeight),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)),
            )
            val layout = measurer.measure(AnnotatedString(tick.label), labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = (x - layout.size.width / 2f)
                        .coerceIn(plotLeft, (size.width - layout.size.width).coerceAtLeast(plotLeft)),
                    y = size.height - axisRowPx + gapPx / 2f,
                ),
            )
        }

        // Discrete states hold their value until the next sample — a diagonal between "locked" and
        // "unlocked" would draw a state that never existed.
        val line = Path().apply {
            moveTo(px(0), py(series.values[0]))
            for (i in 1 until series.values.size) {
                if (!series.numeric) lineTo(px(i), py(series.values[i - 1]))
                lineTo(px(i), py(series.values[i]))
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(px(series.values.size - 1), plotTop + plotHeight)
            lineTo(px(0), plotTop + plotHeight)
            close()
        }
        clipRect(top = plotTop, bottom = plotTop + plotHeight) {
            drawPath(
                area,
                Brush.verticalGradient(
                    colors = listOf(channel.copy(alpha = 0.14f), Color.Transparent),
                    startY = plotTop,
                    endY = plotTop + plotHeight,
                ),
            )
        }
        drawPath(
            line,
            color = channel,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val last = series.values.size - 1
        val lastAt = Offset(px(last), py(series.values[last]))
        drawCircle(channel.copy(alpha = 0.25f), radius = dotRadius * 2f, center = lastAt)
        drawCircle(channel, radius = dotRadius, center = lastAt)
    }
}
