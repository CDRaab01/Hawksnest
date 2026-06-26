package com.hawksnest.core.logic

/**
 * A PULSE data channel, kept as a pure enum here (no Compose) so view-models like [alarmView] stay
 * unit-testable. The UI maps it to a concrete color via `PulseColors` in `ui/theme`.
 *
 * Mirrors the web `Channel` union ("effort" | "strength" | "streak" | "recovery").
 */
enum class Channel { EFFORT, STRENGTH, STREAK, RECOVERY }
