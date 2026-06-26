package com.hawksnest

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.hawksnest.security.BiometricGate
import com.hawksnest.security.Gate
import com.hawksnest.security.LocalBiometricGate
import com.hawksnest.ui.navigation.AppNavGraph
import com.hawksnest.ui.navigation.Screen
import com.hawksnest.ui.theme.HawksnestTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * A [FragmentActivity] (not a plain ComponentActivity) so AndroidX `BiometricPrompt` can attach.
 * The biometric [Gate] is provided over the whole nav graph; sensitive affordances (unlock/disarm)
 * pull it from [LocalBiometricGate].
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val activity = this
        setContent {
            // Dark-first OLED instrument panel; follows the system day/night setting for now.
            HawksnestTheme {
                val gate: Gate = remember {
                    { reason, action -> BiometricGate.run(activity, "Confirm it's you", reason, action) }
                }
                CompositionLocalProvider(LocalBiometricGate provides gate) {
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
}
