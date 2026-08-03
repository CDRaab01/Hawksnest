package com.hawksnest

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.hawksnest.push.PushNav
import com.hawksnest.push.PushNotifier
import com.hawksnest.ui.navigation.AppNavGraph
import com.hawksnest.ui.navigation.Screen
import com.hawksnest.core.logic.ThemePref
import com.hawksnest.core.logic.resolveDarkTheme
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.shortcuts.ShortcutPublisher
import com.hawksnest.util.DevicePrefsStore
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import com.hawksnest.push.NtfyPushService
import com.hawksnest.push.PushSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single Compose host activity. Kept as a [FragmentActivity] (harmless over ComponentActivity)
 * to leave room for future AndroidX fragment-based integrations.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var pushNav: PushNav
    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var devicePrefs: DevicePrefsStore
    @Inject lateinit var shortcutPublisher: ShortcutPublisher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Push may have been refused at process start: Android 12+ forbids starting a foreground
        // service from the background, and this process is often created by a widget tap rather
        // than by the launcher. `HawksnestApp.onCreate` runs once per process, so without a retry
        // from somewhere Android does allow, push would stay down for the life of that process —
        // including after the owner opens the app. Here is that somewhere. Starting an already
        // running listener is a no-op it handles itself.
        lifecycleScope.launch {
            if (pushSettings.enabled.first()) NtfyPushService.start(this@MainActivity)
        }
        // A doorbell notification carries a camera id to open. Route it through PushNav
        // (the nav shell brings Home forward and opens that camera's lightbox) rather
        // than a start destination — a specific camera opens in an overlay, not a route.
        handlePushIntent(intent)
        // A launcher shortcut ("Lock up", "Arm away", "Arm home") arrives as an extra. Performed
        // here rather than in a BroadcastReceiver so it goes through ControlGate like every other
        // user-initiated call — same pending state, same failure snackbar.
        handleShortcutIntent(intent)
        // A widget whose problem the owner can only fix in Settings (no token, token rejected)
        // opens straight there rather than dropping them on Home to find it.
        val start = intent?.getStringExtra(EXTRA_START_ROUTE) ?: Screen.Home.route
        // A temperature widget tap opens that sensor's history chart. Carried as a bare entity id
        // rather than a route because it is navigated TO rather than started AT — see AppNavGraph.
        val openEntity = intent?.getStringExtra(EXTRA_OPEN_ENTITY)
        setContent {
            // Dark-first OLED instrument panel. The default still follows the system day/night
            // setting; Settings → Appearance overrides it (see ThemePref, and its note on why
            // this default differs from web's).
            val pref by devicePrefs.themePref.collectAsState(initial = ThemePref.DEFAULT)
            HawksnestTheme(darkTheme = resolveDarkTheme(pref, isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavGraph(
                        startDestination = start,
                        openEntityId = openEntity,
                        pushNav = pushNav,
                    )
                }
            }
        }
    }

    // Warm deep-link: a tap while the app is already running (SINGLE_TOP) delivers here
    // instead of recreating the activity. Feed it through the same PushNav path.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    private fun handlePushIntent(intent: Intent?) {
        intent?.getStringExtra(PushNotifier.EXTRA_CAMERA)?.let { cameraId ->
            // The event is optional — doorbell/alarm taps carry none and just open
            // the camera live, as before.
            pushNav.openCamera(cameraId, intent.getStringExtra(PushNotifier.EXTRA_EVENT))
        }
    }

    private fun handleShortcutIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(ShortcutPublisher.EXTRA_SHORTCUT) ?: return
        // Consume it, so a configuration change that re-delivers the intent cannot re-fire the
        // action — locking twice is harmless, but arming twice is a state change the owner
        // did not ask for.
        intent.removeExtra(ShortcutPublisher.EXTRA_SHORTCUT)
        lifecycleScope.launch { shortcutPublisher.perform(id) }
    }

    companion object {
        /** Nav route to open on launch, set by the home-screen widgets' error states. */
        const val EXTRA_START_ROUTE = "com.hawksnest.START_ROUTE"

        /** Entity id whose detail + history chart to open, set by the temperature widget's tap. */
        const val EXTRA_OPEN_ENTITY = "com.hawksnest.OPEN_ENTITY"
    }
}
