package com.hawksnest.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.logic.ALARM_TRANSITIONAL
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.TilePicture
import com.hawksnest.core.logic.armButtonEnabled
import com.hawksnest.core.logic.alarmView
import com.hawksnest.core.logic.aspectFromDimensions
import com.hawksnest.core.logic.DEFAULT_ASPECT
import com.hawksnest.core.logic.cameraCountLabel
import com.hawksnest.core.logic.isWideAspect
import com.hawksnest.core.logic.tilePicture
import com.hawksnest.core.logic.wallRows
import com.hawksnest.core.logic.graceExpired
import com.hawksnest.core.logic.relativeTime
import com.hawksnest.core.logic.snapshotBucket
import com.hawksnest.ui.cameras.DoorbellBanner
import com.hawksnest.ui.cameras.CameraSnapshot
import com.hawksnest.ui.cameras.LiveFrameStore
import com.hawksnest.ui.cameras.bustCache
import com.hawksnest.ui.components.ConnectionPill
import com.hawksnest.ui.components.OfflineState
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.ReconnectingBanner
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.color
import kotlinx.coroutines.delay

/** Per-arm-mode glyph (Ring uses a distinct icon per mode). */
private val ARM_ICON: Map<String, ImageVector> = mapOf(
    "alarm_disarm" to Icons.Filled.LockOpen,
    "alarm_arm_home" to Icons.Filled.Home,
    "alarm_arm_away" to Icons.Filled.Lock,
)

/**
 * Home — a glanceable, camera-forward landing screen (Ring-style), mirroring the web Dashboard:
 * three big circular arm buttons + a one-line security read-out, the 2-up camera grid, and a single
 * compact "Rooms" entry. Device controls live one tap deeper (Rooms → area detail).
 */
@Composable
fun HomeScreen(
    onOpenRooms: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val pending by viewModel.pending.collectAsState()
    // Snapshot cache-busters, matching the web SnapshotBucketProvider 1:1.
    //
    // SEEDED FROM THE CLOCK, not 0. Starting at 0 meant every app launch requested
    // `..._=0` — byte-identical to the previous launch's first request — so Coil could
    // serve the opening frame straight from its disk cache. The tile then showed a
    // genuinely old picture, not a merely-10s-stale one. The seed only has to DIFFER
    // between sessions (it is a cache-buster, not an ordering key); increments stay
    // monotonic within a session, so a backward clock jump still can't repeat a bucket.
    //
    // `opens` bumps on every ON_RESUME so Frigate tiles refetch the moment the app is
    // opened rather than waiting up to 10s for the next beat. Ring rides `shared` only —
    // its proxy is metered and battery cams republish every 300s, so an extra fetch on
    // open buys the same image twice. `snapshotBucket()` picks per camera.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bucketSeed = remember { System.currentTimeMillis() / 1000 }
    var ticks by remember { mutableStateOf(0L) }
    var opens by remember { mutableStateOf(0L) }
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // addObserver replays up to the current state, so this also fires for the
                // initial composition — which IS an app open, and should refresh.
                Lifecycle.Event.ON_RESUME -> { resumed = true; opens += 1 }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Ticking stops while backgrounded — no point spending radio on tiles nobody sees.
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            delay(10_000)
            ticks += 1
        }
    }
    val sharedBucket = bucketSeed + ticks
    val onOpenBucket = bucketSeed + ticks + opens
    // Deep-link from a tapped notification: open that camera's lightbox once the
    // camera list has loaded. Consume it either way so it fires exactly once (a not-yet-known
    // camera just lands on Home rather than looping).
    val pushCamera by viewModel.pushCameraTarget.collectAsState()
    LaunchedEffect(pushCamera, ui.cameras) {
        val target = pushCamera
        // Wait for the camera list before acting; once we can, open the match (if any)
        // and consume so it fires exactly once (unknown camera → just lands on Home).
        if (target != null && !target.isFresh(System.currentTimeMillis())) {
            viewModel.consumePushTarget()
        } else if (target != null && ui.cameras.isNotEmpty()) {
            ui.cameras.firstOrNull { it.id == target.cameraId }?.let {
                // eventId null for doorbell/alarm taps — those open live, as before.
                viewModel.openLightbox(ui.cameras, it, target.eventId, target.start)
            }
            viewModel.consumePushTarget()
        }
    }
    // The lightbox renders at the nav-graph root off CameraSession (so system PiP can show it),
    // but Home stays the active destination underneath and keeps recomposing — keep the
    // in-player switcher's camera list (and its snapshot URLs) fresh, as the old Dialog's
    // capture-by-recomposition did.
    LaunchedEffect(ui.cameras) { viewModel.updateLightboxCameras(ui.cameras) }

    // Doorbell banner: show the latest ring until dismissed or auto-timeout.
    var doorbellDismissedAt by remember { mutableStateOf(0L) }
    val ring = ui.doorbell
    val showDoorbell = ring != null && ring.whenMs > doorbellDismissedAt
    LaunchedEffect(showDoorbell, ring?.whenMs) {
        if (showDoorbell && ring != null) {
            kotlinx.coroutines.delay(12_000)
            doorbellDismissedAt = ring.whenMs
        }
    }

    // ── Honest degraded offline model (core/logic/Offline.kt) ────────────────────────────────
    // After an in-session drop we keep the last in-memory entities on screen — dimmed, controls
    // disabled, under a "Reconnecting — as of HH:MM" banner — for at most the 120s grace window
    // (lock/alarm state is already masked at the store the moment the socket drops). Beyond the
    // window, or on a terminal auth error, collapse to the full OfflineState. Nothing is
    // persisted; a first-ever connect (nothing stale to show) keeps the plain connecting UI.
    val staleSince by viewModel.staleSinceMs.collectAsState()
    val lastConnected by viewModel.lastConnectedMs.collectAsState()
    val nextRetryAt by viewModel.nextRetryAtMs.collectAsState()
    val hostReachable by viewModel.hostReachable.collectAsState()
    val lastUpdate by viewModel.lastUpdateMs.collectAsState()
    val disconnected = ui.status == ConnectionStatus.ERROR ||
        (ui.status == ConnectionStatus.CONNECTING && staleSince != null)
    // 1s heartbeat while disconnected so the grace window actually expires on screen.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(disconnected) {
        if (!disconnected) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val inGrace = ui.status == ConnectionStatus.CONNECTING &&
        staleSince?.let { !graceExpired(it, nowMs) } == true
    val showOffline = ui.status == ConnectionStatus.ERROR ||
        (ui.status == ConnectionStatus.CONNECTING && staleSince?.let { graceExpired(it, nowMs) } == true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hawksnest",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ConnectionPill(ui.status)
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showOffline) {
            // Offline = "we don't know" — no entity data at all, just the honest readouts.
            // The header stays above so Settings (fix the URL/token) is always reachable.
            OfflineState(
                lastConnectedMs = lastConnected,
                nextRetryAtMs = nextRetryAt,
                hostReachable = hostReachable,
                onRetry = viewModel::retryNow,
                error = ui.error?.takeIf { ui.status == ConnectionStatus.ERROR },
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        if (inGrace) {
            ReconnectingBanner(asOfMs = lastUpdate.takeIf { it > 0 })
        }

        // A tapped triggered-alarm alert, in its own words, above the hero where Off is.
        val alert by viewModel.alertBanner.collectAsState()
        alert?.let { a -> AlertBannerCard(a, onDismiss = viewModel::dismissAlert) }

        HomeContent(
            ui = ui,
            pending = pending,
            sharedBucket = sharedBucket,
            onOpenBucket = onOpenBucket,
            showDoorbell = showDoorbell,
            controlsEnabled = !inGrace,
            onOpenRooms = onOpenRooms,
            onArm = viewModel::arm,
            onOpenLightbox = { viewModel.openLightbox(ui.cameras, it) },
            onDoorbellDismiss = { ring?.let { doorbellDismissedAt = it.whenMs } },
            modifier = if (inGrace) Modifier.alpha(0.55f) else Modifier,
        )
    }
}

/**
 * Everything below Home's header — split out so the offline/grace branch above stays readable.
 * During the grace window the whole block renders dimmed with [controlsEnabled] false.
 */
@Composable
private fun HomeContent(
    ui: HomeUi,
    pending: Set<String>,
    sharedBucket: Long,
    onOpenBucket: Long,
    showDoorbell: Boolean,
    controlsEnabled: Boolean,
    onOpenRooms: () -> Unit,
    onArm: (String) -> Unit,
    onOpenLightbox: (CameraUi) -> Unit,
    onDoorbellDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each camera's measured picture shape, learned from its decoded snapshot — no per-camera
    // resolution table (core/logic/MediaAspect.kt). `wideCameraIds` is the subset wide enough to
    // deserve a full-width row; both only ever grow, which is what stops a snapshot refresh from
    // looping recomposition.
    var cameraAspects by remember { mutableStateOf(mapOf<String, Float>()) }
    var wideCameraIds by remember { mutableStateOf(setOf<String>()) }
    // What each tile's own snapshot fetches have seen, by camera id. Hoisted out of the tiles so
    // the header counts exactly the states the tiles show; the two used to disagree ("14/14 live"
    // over a black tile).
    val fetches = remember { mutableStateMapOf<String, TileFetch>() }
    // Recomputed on every refresh tick (the buckets change every 10 s), which is also what lets a
    // picture age into STALE while nothing else on screen changes.
    val nowMs = System.currentTimeMillis()
    val ring = ui.doorbell
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.lg),
    ) {
        if (showDoorbell && ring != null) {
            DoorbellBanner(
                cameraName = ring.name,
                onView = {
                    ui.cameras.firstOrNull { it.id == ring.cameraId }?.let { onOpenLightbox(it) }
                    onDoorbellDismiss()
                },
                onDismiss = onDoorbellDismiss,
            )
        }

        if (ui.lifeSafetyAlerts.isNotEmpty() || ui.lifeSafetyMonitored > 0) {
            LifeSafetyStrip(ui)
        }

        SecurityHero(
            ui,
            inFlight = ui.alarmEntityId?.let { it in pending } == true,
            enabled = controlsEnabled,
            onArm = onArm,
            onDisarm = { onArm("alarm_disarm") },
        )

        if (ui.cameras.isNotEmpty()) {
            SectionHeader(
                title = "Cameras",
                channel = HawksnestTheme.pulse.effort,
                trailing = {
                    Text(
                        cameraCountLabel(ui.cameras.map { tileView(it, fetches[it.id], nowMs).state }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            // A dual-lens panorama gets a full-width row instead of a cropped half-width cell —
            // the Compose answer to the web wall's `col-span` (see core/logic/MediaAspect.kt).
            // Which cameras are wide is learned from the decoded snapshots, not a table.
            wallRows(ui.cameras) { it.id in wideCameraIds }.forEach { rowCams ->
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    rowCams.forEach { cam ->
                        CameraTile(
                            cam = cam,
                            view = tileView(cam, fetches[cam.id], nowMs),
                            // A Frigate camera that sleeps (battery, behind a Home Hub) rides the
                            // shared beat like Ring: its frame is not current the instant it is
                            // asked for, so the on-open tick would buy the same stale image twice.
                            snapshotModel = bustCache(cam.snapshotUrl, snapshotBucket(cam.isFrigate && !cam.isBattery, sharedBucket, onOpenBucket)),
                            onSnapshotResult = { ok ->
                                val before = fetches[cam.id] ?: TileFetch()
                                fetches[cam.id] = if (ok) {
                                    TileFetch(okAtMs = System.currentTimeMillis(), failed = false)
                                } else {
                                    before.copy(failed = true)
                                }
                            },
                            onClick = { onOpenLightbox(cam) },
                            aspect = cameraAspects[cam.id] ?: DEFAULT_ASPECT,
                            onAspect = { ratio ->
                                // Only ever GROW the set, and only on a real change, so a snapshot
                                // refresh cannot loop recomposition (the web wall's `markWide`).
                                if (isWideAspect(ratio) && cam.id !in wideCameraIds) {
                                    wideCameraIds = wideCameraIds + cam.id
                                }
                                if (cameraAspects[cam.id] != ratio) {
                                    cameraAspects = cameraAspects + (cam.id to ratio)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCams.size == 1 && rowCams[0].id !in wideCameraIds) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (ui.roomCount > 0) {
            SectionHeader("Rooms", channel = HawksnestTheme.pulse.recovery)
            PanelCard(onClick = onOpenRooms) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${ui.roomCount} ${if (ui.roomCount == 1) "room" else "rooms"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            ui.roomsPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Life-safety (smoke/CO/gas/leak) — an always-on channel surfaced regardless of armed state. A
 * triggered sensor shows a prominent streak-channel alert; otherwise a quiet monitored "all clear".
 */
@Composable
private fun LifeSafetyStrip(ui: HomeUi) {
    val pulse = HawksnestTheme.pulse
    val alert = ui.lifeSafetyAlerts.isNotEmpty()
    val channel = if (alert) pulse.streak else pulse.recovery
    PanelCard(channel = channel) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(channel))
            Spacer(Modifier.size(HawksnestTheme.spacing.sm))
            Text(
                if (alert) {
                    "Life-safety: ${ui.lifeSafetyAlerts.joinToString(" · ")}"
                } else {
                    "Life-safety: all clear · ${ui.lifeSafetyMonitored} monitored"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (alert) channel else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SecurityHero(
    ui: HomeUi,
    /** This app's own arm/disarm call is still waiting on HA. */
    inFlight: Boolean,
    onArm: (String) -> Unit,
    onDisarm: () -> Unit,
    enabled: Boolean = true,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    // Which circle was tapped, so only its spinner shows while HA arms/disarms. Cleared on settle.
    // The spinner covers HA's exit delay too; the buttons' enabled state does not (see
    // armButtonEnabled — Off has to stay live through a countdown).
    var tapped by remember { mutableStateOf<String?>(null) }
    val settling = inFlight || ui.alarmRawState in ALARM_TRANSITIONAL
    LaunchedEffect(settling) { if (!settling) tapped = null }
    // While reconnecting the alarm entity is masked to `unavailable` (never a stale mode), so no
    // circle reads active; drop the channel tint too so the hero doesn't imply a posture.
    PanelCard(channel = if (enabled) ui.alarm?.let { pulse.color(it.channel) } else null, raised = true) {
        if (ui.alarm != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xl, Alignment.CenterHorizontally),
            ) {
                ARM_BUTTONS.forEach { b ->
                    ArmCircle(
                        label = b.label,
                        icon = ARM_ICON[b.service] ?: Icons.Filled.LockOpen,
                        active = enabled && ui.alarmRawState == b.state,
                        channel = pulse.color(alarmView(b.state).channel),
                        busy = settling && tapped == b.service,
                        enabled = enabled && armButtonEnabled(b, ui.alarmRawState, inFlight),
                        onClick = {
                            haptics.toggleOn()
                            tapped = b.service
                            if (b.service == "alarm_disarm") onDisarm() else onArm(b.service)
                        },
                    )
                }
            }
        } else {
            Text(
                "No alarm panel",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(HawksnestTheme.spacing.md))
        // One Text, not a Row of two. Side-by-side Texts are each measured against what the other
        // leaves behind, so once the summary wrapped, the offline label was squeezed to a
        // one-character-wide column running the height of the card. As a single annotated string
        // the whole read-out wraps as one paragraph (what the web twin gets from inline spans);
        // the offline half keeps its smaller type via a span.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (ui.secureAllClear) pulse.recovery else pulse.streak)) {
                    append(ui.securitySummary)
                }
                ui.offlineLabel?.let {
                    withStyle(
                        MaterialTheme.typography.bodySmall.toSpanStyle().copy(color = pulse.streak),
                    ) {
                        append(" · $it")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ArmCircle(
    label: String,
    icon: ImageVector,
    active: Boolean,
    channel: Color,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val pulse = HawksnestTheme.pulse
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (active) channel else pulse.panelHigh)
                .then(
                    if (active) Modifier
                    else Modifier.border(1.dp, pulse.hairline, CircleShape),
                )
                .clickable(onClick = onClick, enabled = enabled && !busy),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else channel,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.size(HawksnestTheme.spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) channel else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraTile(
    cam: CameraUi,
    view: TileView,
    snapshotModel: String?,
    onSnapshotResult: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The picture's measured shape; [DEFAULT_ASPECT] until a frame decodes. */
    aspect: Float = DEFAULT_ASPECT,
    /** Reports that shape back up so the wall can give a panorama its own row. */
    onAspect: ((Float) -> Unit)? = null,
) {
    val pulse = HawksnestTheme.pulse
    val name = cam.name
    val live = cam.live
    PanelCard(modifier = modifier, contentPadding = 0.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clickable(onClick = onClick),
        ) {
            // Crop is right for a normal camera: the cell is 16:9, the picture is 16:9, and
            // filling it beats hairline bars. It is wrong for a 32:9 panorama, where it kept only
            // the centre ~50% and cropped one lens off entirely.
            val scale = if (isWideAspect(aspect)) ContentScale.Fit else ContentScale.Crop
            // The snapshot always keeps fetching underneath, even while a live frame covers it, so
            // it can take over once it is the newer picture. A parked battery camera gets no fetch
            // at all: Frigate answers with its grey error image (at HTTP 200) while the pipeline
            // is off, which would decode as a "frame"; its last live frame is the truthful picture.
            CameraSnapshot(
                model = if (cam.asleep) null else snapshotModel,
                modifier = Modifier.fillMaxSize(),
                contentScale = scale,
                onAspect = onAspect,
                onResult = onSnapshotResult,
            )
            // The frame grabbed while the owner last watched this camera live (LiveFrameStore),
            // shown only while it is newer than the snapshot. It used to win for the life of the
            // process, so a camera watched this morning showed this morning's picture all day.
            val liveFrame = view.liveFrame
            if (liveFrame != null) {
                LaunchedEffect(liveFrame.bitmap.width, liveFrame.bitmap.height) {
                    aspectFromDimensions(liveFrame.bitmap.width, liveFrame.bitmap.height)
                        ?.let { onAspect?.invoke(it) }
                }
                Image(
                    bitmap = liveFrame.bitmap,
                    contentDescription = "Camera snapshot",
                    contentScale = scale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A camera HA reports unavailable (a closed/offline Ring camera that can't serve a
            // frame) gets a clear "No signal" over the dimmed last frame. This is HA's reliable
            // state — we deliberately do NOT infer staleness from timestamps, because ring-mqtt
            // doesn't expose a capture time and HA's entity_picture token rotation makes
            // last_updated read "fresh" even on a stale camera. A sleeping battery camera is the
            // other reliable state (`asleep`, from the camera entity being `idle`).
            if (!live || cam.asleep) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (!live) "No signal" else "Asleep",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(HawksnestTheme.spacing.sm)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
            ) {
                // Green only for a picture that is actually current (see core/logic/CameraTiles.kt).
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (view.state == TilePicture.LIVE) pulse.recovery else Color.White.copy(alpha = 0.4f)))
                // The age of the PICTURE: when a Frigate snapshot was fetched, a Ring snapshot's own
                // update time, or when a live frame was grabbed. Never HA's token rotation, which
                // made every wired tile read "2m ago" at once. A sleeping camera's last picture is
                // old by design, so it reports when its motion sensor last moved instead.
                val age = view.pictureAtMs?.let { relativeTime(it) }
                Text(
                    when (view.state) {
                        TilePicture.ASLEEP -> cam.motionChangedMs?.let { "Motion ${relativeTime(it)}" } ?: "Asleep"
                        TilePicture.NO_SIGNAL -> "—"
                        TilePicture.LOADING -> "Loading"
                        TilePicture.LIVE -> age ?: "Live"
                        TilePicture.STALE -> if (view.refreshFailed) "Not updating" + (age?.let { " · $it" } ?: "") else age ?: "Stale"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(HawksnestTheme.spacing.sm),
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

/** What a tile's own snapshot fetches have seen: when a frame last decoded, and whether the latest
 *  refresh failed. */
private data class TileFetch(val okAtMs: Long? = null, val failed: Boolean = false)

/** Everything a tile shows about its picture, computed once and shared with the header count. */
private data class TileView(
    val state: TilePicture,
    val pictureAtMs: Long?,
    val refreshFailed: Boolean,
    /** The grabbed live frame, when it is the newer picture and should cover the snapshot. */
    val liveFrame: com.hawksnest.ui.cameras.LiveFrame?,
)

/**
 * Which picture a tile shows and how old it is. A ring-mqtt snapshot is a stored image, so its age
 * is the entity's own update time; every other camera's snapshot (Frigate, the Reolink integration)
 * is grabbed when fetched, so its age is the fetch. A live frame grabbed while watching wins only
 * while it is newer.
 */
private fun tileView(cam: CameraUi, fetch: TileFetch?, nowMs: Long): TileView {
    val loadedAt = fetch?.okAtMs
    val snapshotAt = when {
        loadedAt == null -> null
        cam.storedSnapshot -> cam.lastChangedMs
        else -> loadedAt
    }
    val grabbed = LiveFrameStore.get(cam.id)
    val showGrabbed = grabbed != null && (cam.asleep || loadedAt == null || snapshotAt == null || grabbed.capturedAtMs > snapshotAt)
    val refreshFailed = !showGrabbed && fetch?.failed == true
    val pictureAt = if (showGrabbed) grabbed!!.capturedAtMs else snapshotAt
    return TileView(
        state = tilePicture(
            haAvailable = cam.live,
            asleep = cam.asleep,
            hasPicture = showGrabbed || loadedAt != null,
            lastFetchFailed = refreshFailed,
            pictureAtMs = pictureAt,
            nowMs = nowMs,
        ),
        pictureAtMs = pictureAt,
        refreshFailed = refreshFailed,
        liveFrame = grabbed.takeIf { showGrabbed },
    )
}

/**
 * The alert a triggered-alarm notification carried, pinned over Home until dismissed. It says what
 * the notification said (HA's words, so it names the sensor when HA did) and nothing more: the
 * hero right below it is where the owner acts. Critical colour, because a triggered alarm is the
 * one state that means "deal with this now".
 */
@Composable
private fun AlertBannerCard(alert: com.hawksnest.push.AlertBanner, onDismiss: () -> Unit) {
    val critical = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(critical.copy(alpha = 0.12f))
            .border(1.dp, critical.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(alert.title, style = MaterialTheme.typography.titleMedium, color = critical)
            if (alert.body.isNotBlank()) {
                Text(
                    alert.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss alert",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
