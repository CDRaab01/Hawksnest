package com.hawksnest

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.hawksnest.ui.navigation.AppNavGraph
import com.hawksnest.ui.navigation.Screen
import com.hawksnest.ui.theme.HawksnestTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Compose host activity. Kept as a [FragmentActivity] (harmless over ComponentActivity)
 * to leave room for future AndroidX fragment-based integrations.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Dark-first OLED instrument panel; follows the system day/night setting for now.
            HawksnestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavGraph(startDestination = Screen.Home.route)
                }
            }
        }
    }
}
