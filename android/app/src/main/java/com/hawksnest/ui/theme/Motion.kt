package com.hawksnest.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion tokens — every animation in the app draws its duration and easing from here so the whole
 * UI moves with one hand.
 *
 *  - Fast: micro feedback (press tints, checkmarks)
 *  - Standard: state changes (expand/collapse, color shifts)
 *  - Emphasized: structural moves (panels entering, mode switches)
 *  - Data: numeral rolls, ring sweeps, chart draws — deliberately slower so values feel measured
 */
object PulseMotion {
    const val FAST = 120
    const val STANDARD = 240
    const val EMPHASIZED = 400
    const val DATA = 600

    /** Standard accelerate-then-settle, used for most state changes. */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Strong deceleration — entrances and data sweeps. */
    val EaseDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> fast(): TweenSpec<T> = tween(FAST, easing = Ease)
    fun <T> standard(): TweenSpec<T> = tween(STANDARD, easing = Ease)
    fun <T> emphasized(): TweenSpec<T> = tween(EMPHASIZED, easing = EaseDecel)
    fun <T> data(): TweenSpec<T> = tween(DATA, easing = EaseDecel)

    /** Press-scale spring shared by tappable surfaces. */
    val SpringPress: SpringSpec<Float> = spring(
        dampingRatio = 0.5f,
        stiffness = 600f,
    )
}
