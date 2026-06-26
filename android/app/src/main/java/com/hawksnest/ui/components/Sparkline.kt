package com.hawksnest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * A bare polyline trend — no axes, no labels, no dots except an emphasized last point. Lives
 * inside stat tiles and record rows to give a number its recent history at a glance.
 */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    channel: Color = HawksnestTheme.pulse.effort,
    strokeWidth: Dp = 2.dp,
    fill: Boolean = true,
    emphasizeLast: Boolean = true,
) {
    if (points.size < 2) return
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val dotRadius = strokePx * 1.6f
        val stepX = (size.width - dotRadius * 2) / (points.size - 1)
        val usableH = size.height - dotRadius * 2

        fun pointAt(i: Int): Offset {
            val norm = (points[i] - min) / range
            return Offset(dotRadius + stepX * i, dotRadius + usableH * (1f - norm))
        }

        val line = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until points.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(line)
                lineTo(pointAt(points.size - 1).x, size.height)
                lineTo(pointAt(0).x, size.height)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    colors = listOf(channel.copy(alpha = 0.14f), Color.Transparent),
                ),
            )
        }
        drawPath(
            line,
            color = channel,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (emphasizeLast) {
            drawCircle(channel.copy(alpha = 0.25f), radius = dotRadius * 2f, center = pointAt(points.size - 1))
            drawCircle(channel, radius = dotRadius, center = pointAt(points.size - 1))
        }
    }
}
