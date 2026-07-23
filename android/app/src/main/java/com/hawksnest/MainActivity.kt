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
import com.hawksnest.ui.theme.HawksnestTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single Compose host activity. Kept as a [FragmentActivity] (harmless over ComponentActivity)
 * to leave room for future AndroidX fragment-based integrations.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var pushNav: PushNav

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A doorbell notification carries a camera id to open. Route it through PushNav
        // (the nav shell brings Home forward and opens that camera's lightbox) rather
        // than a start destination — a specific camera opens in an overlay, not a route.
        handlePushIntent(intent)
        // A widget whose problem the owner can only fix in Settings (no token, token rejected)
        // opens straight there rather than dropping them on Home to find it.
        val start = intent?.getStringExtra(EXTRA_START_ROUTE) ?: Screen.Home.route
        setContent {
            // Dark-first OLED instrument panel; follows the system day/night setting for now.
            HawksnestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavGraph(startDestination = start, pushNav = pushNav)
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
        intent?.getStringExtra(PushNotifier.EXTRA_CAMERA)?.let { pushNav.openCamera(it) }
    }

    companion object {
        /** Nav route to open on launch, set by the home-screen widgets' error states. */
        const val EXTRA_START_ROUTE = "com.hawksnest.START_ROUTE"
    }
}
