package com.hawksnest

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.hawksnest.push.PushNav
import com.hawksnest.push.PushNotifier
import com.hawksnest.ui.navigation.AppNavGraph
import com.hawksnest.core.logic.CameraStart
import com.hawksnest.core.logic.ThemePref
import com.hawksnest.core.logic.pipAspect
import com.hawksnest.core.logic.resolveDarkTheme
import com.hawksnest.ui.cameras.CameraSession
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.shortcuts.ShortcutPublisher
import com.hawksnest.util.DevicePrefsStore
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.hawksnest.push.NtfyPushService
import com.hawksnest.push.PushSettings
import kotlinx.coroutines.flow.combine
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
    @Inject lateinit var cameraSession: CameraSession

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
        // Keep the PiP params current with the camera session. On Android 12+ this is what makes
        // "live only" hold for the swipe-home gesture: setAutoEnterEnabled flips off the moment
        // the owner scrubs to a recording or closes the player, and back on at Live — there is no
        // callback moment to decide in (onUserLeaveHint isn't used by the gesture's auto-enter).
        // The aspect ratio follows the source video so the window isn't letterboxed.
        lifecycleScope.launch {
            combine(cameraSession.open, cameraSession.isLive, cameraSession.videoSize) { _, _, _ -> }
                .collect { setPictureInPictureParams(pipParams()) }
        }
        // Every "open the app somewhere" intent — notification taps and buttons, widget taps,
        // launcher shortcuts — goes through one handler, here and in onNewIntent alike. Skipped when
        // the activity is being recreated (rotation, theme change): the intent it restores is the
        // one already handled, and re-running it would open the same camera or fire the same
        // shortcut a second time.
        if (savedInstanceState == null) handleDeepLinkIntent(intent)
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
                        pushNav = pushNav,
                        cameraSession = cameraSession,
                    )
                }
            }
        }
    }

    /** The current PiP window shape + auto-enter gate, rebuilt from the camera session. */
    private fun pipParams(): PictureInPictureParams {
        val size = cameraSession.videoSize.value
        val (w, h) = pipAspect(size?.first, size?.second)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(cameraSession.wantsPip())
                    // Video content: crossfade between window sizes rather than stretching frames.
                    setSeamlessResizeEnabled(false)
                }
            }
            .build()
    }

    // Pre-31 there is no auto-enter, so the home button lands here and we minimize a live camera
    // by hand. 31+ deliberately does NOT also call enterPictureInPictureMode: auto-enter already
    // covers home AND the gesture with the smooth transition, and a redundant manual call animates
    // as a jarring double-jump.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && cameraSession.wantsPip()) {
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // CameraPlayer hides all chrome off this flag (the same pattern as fullscreen), so the
        // tiny window shows only the video frame.
        cameraSession.setInPip(isInPictureInPictureMode)
        // Leaving PiP with the activity already STOPPED is the canonical "dismissed via the X"
        // signature (expanding back to the app leaves PiP RESUMED/STARTED). Close the session so
        // the overlay unmounts and composition disposal tears down the stream — otherwise the
        // player keeps streaming (and could keep sounding) invisibly in the background task.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            cameraSession.close()
        }
    }

    // Warm deep-link: a tap while the app is already running (SINGLE_TOP) delivers here
    // instead of recreating the activity. It goes through exactly the same handler as a cold start,
    // which is the point: a widget or notification tap must land the same whether the app was
    // open or not.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Turn whatever an intent asks for into a PushNav target (or a shortcut run). The nav shell
     * acts on the target from any screen. Each extra is removed once read, so the intent Android
     * keeps on the activity cannot replay it.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        intent ?: return
        // A notification button that opened the app. Unlike a tap on the notification itself, a
        // button doesn't clear it, so it would sit in the shade after it was dealt with.
        intent.getIntExtra(PushNotifier.EXTRA_NOTIFICATION_ID, 0).takeIf { it != 0 }?.let { id ->
            NotificationManagerCompat.from(this).cancel(id)
        }
        intent.getStringExtra(PushNotifier.EXTRA_ALERT_TITLE)?.let { title ->
            // Set before any camera, so an alert that also names one still lands on Home with its
            // banner, and the camera opens over it.
            pushNav.showAlert(title, intent.getStringExtra(PushNotifier.EXTRA_ALERT_BODY).orEmpty())
        }
        intent.getStringExtra(EXTRA_OPEN_ENTITY)?.let { pushNav.openEntity(it) }
        intent.getStringExtra(EXTRA_START_ROUTE)?.let { pushNav.openRoute(it) }
        intent.getStringExtra(PushNotifier.EXTRA_CAMERA)?.let { cameraId ->
            // The event is optional — doorbell/alarm taps carry none and just open
            // the camera live, as before.
            val start = intent.getStringExtra(PushNotifier.EXTRA_CAMERA_START)
                ?.let { name -> CameraStart.entries.firstOrNull { it.name == name } }
                ?: CameraStart.LIVE
            pushNav.openCamera(cameraId, intent.getStringExtra(PushNotifier.EXTRA_EVENT), start)
        }
        // A launcher shortcut ("Lock up", "Arm away", "Arm home"). Performed here rather than in a
        // BroadcastReceiver so it goes through ControlGate like every other user-initiated call —
        // same pending state, same failure snackbar.
        intent.getStringExtra(ShortcutPublisher.EXTRA_SHORTCUT)?.let { id ->
            lifecycleScope.launch { shortcutPublisher.perform(id) }
        }
        listOf(
            PushNotifier.EXTRA_ALERT_TITLE, PushNotifier.EXTRA_ALERT_BODY, EXTRA_OPEN_ENTITY,
            EXTRA_START_ROUTE, PushNotifier.EXTRA_CAMERA, PushNotifier.EXTRA_EVENT,
            PushNotifier.EXTRA_CAMERA_START, PushNotifier.EXTRA_NOTIFICATION_ID, ShortcutPublisher.EXTRA_SHORTCUT,
        ).forEach(intent::removeExtra)
    }

    companion object {
        /** Nav route to open (Settings), set by the home-screen widgets' error states. */
        const val EXTRA_START_ROUTE = "com.hawksnest.START_ROUTE"

        /** Entity id whose screen to open, set by widget taps and device notifications. */
        const val EXTRA_OPEN_ENTITY = "com.hawksnest.OPEN_ENTITY"
    }
}
