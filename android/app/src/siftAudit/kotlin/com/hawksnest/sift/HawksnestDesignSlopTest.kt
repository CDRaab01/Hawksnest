package com.hawksnest.sift

import com.hawksnest.ui.theme.HawksnestTheme
import style.sift.compose.DesignSlopSuite
import style.sift.compose.TokenScan
import style.sift.core.config.SiftConfig
import java.io.File

/**
 * Runs the Sift design-slop audit over Hawksnest's Compose UI.
 *
 * Each scene is rendered on Robolectric (NATIVE graphics) wrapped in the real [HawksnestTheme], in
 * both dark and light variants, then normalized to a `UiSnapshot` and run through Sift's rule
 * catalog. Token rules (fonts/palette) read the real theme via [TokenScan]; copy/contrast/touch
 * rules read the rendered scenes. The single inherited `audit()` test writes
 * `app/build/sift/report.json` and fails only on surviving `error`-severity findings (per
 * `.sift/config.json` + baseline).
 *
 * Wiring: this source set (`src/siftAudit`) and its `style.sift:sift-compose` dependency are added
 * only when the Sift composite build is present (see app/build.gradle.kts `siftAvailable`), so a
 * standalone Hawksnest checkout still builds and unit-tests without Sift.
 */
class HawksnestDesignSlopTest : DesignSlopSuite(
    config = SiftConfig.fromFileOrDefault(),
    tokens = TokenScan.scan(listOf(File("src/main/java/com/hawksnest/ui/theme"))),
) {
    init {
        scene("security-hero", dark = true) { HawksnestTheme(darkTheme = true) { SecurityHeroScene() } }
        scene("security-hero", dark = false) { HawksnestTheme(darkTheme = false) { SecurityHeroScene() } }
        scene("controls", dark = true) { HawksnestTheme(darkTheme = true) { ControlsScene() } }
        scene("controls", dark = false) { HawksnestTheme(darkTheme = false) { ControlsScene() } }
        scene("widgets", dark = true) { HawksnestTheme(darkTheme = true) { WidgetsScene() } }
        scene("widgets", dark = false) { HawksnestTheme(darkTheme = false) { WidgetsScene() } }
        scene("settings", dark = true) { HawksnestTheme(darkTheme = true) { SettingsScene() } }
        scene("settings", dark = false) { HawksnestTheme(darkTheme = false) { SettingsScene() } }
    }
}
