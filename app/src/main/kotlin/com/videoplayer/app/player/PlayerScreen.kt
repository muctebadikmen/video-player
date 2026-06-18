package com.videoplayer.app.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videoplayer.app.engine.Media3PlaybackEngine
import com.videoplayer.app.player.controls.DoubleTapAction
import com.videoplayer.app.player.controls.SKIP_MS
import com.videoplayer.app.player.controls.doubleTapAction
import com.videoplayer.app.player.controls.resolveTapZone
import com.videoplayer.app.player.controls.seekTarget
import com.videoplayer.app.player.gestures.AspectMode
import com.videoplayer.app.player.gestures.BOOST_SPEED
import com.videoplayer.app.player.gestures.VerticalSide
import com.videoplayer.app.player.gestures.applyBrightness
import com.videoplayer.app.player.gestures.applyVolumeFactor
import com.videoplayer.app.player.gestures.displayLabel
import com.videoplayer.app.player.gestures.horizontalSeekDeltaMs
import com.videoplayer.app.player.gestures.nextAspectMode
import com.videoplayer.app.player.gestures.verticalSide
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration
import com.videoplayer.core.model.nextInFolder
import com.videoplayer.core.playback.AbLoop
import com.videoplayer.core.playback.FRAME_STEP_MS
import com.videoplayer.core.playback.LOCK_HINT_VISIBLE_MS
import com.videoplayer.core.playback.OrientationMode
import com.videoplayer.core.playback.PlayerStatus
import com.videoplayer.core.playback.UNLOCK_HOLD_MS
import com.videoplayer.core.playback.abLoopTarget
import com.videoplayer.core.playback.clampPipAspect
import com.videoplayer.core.playback.clampSpeed
import com.videoplayer.core.playback.isSleepExpired
import com.videoplayer.core.playback.nextOrientationMode
import com.videoplayer.core.playback.pipAvailable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val AUTO_HIDE_MS = 3_000L
private const val GESTURE_OVERLAY_MS = 800L

// TODO: make a setting in P1.H
private const val AUTO_ADVANCE_DEFAULT = true

/**
 * Full-screen playback for a single [MediaItem]. Owns a [Media3PlaybackEngine],
 * renders a custom Compose control overlay, and handles gestures: tap toggles
 * controls; double-tap seeks ±10s (sides) or play/pauses (center); left-half
 * vertical drag changes brightness, right-half changes volume (to 200%). Each
 * gesture shows a transient [GestureOverlay].
 */
@Composable
fun PlayerScreen(
    item: MediaItem,
    playlist: List<MediaItem>,
    onAdvance: (MediaItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val engine = remember(item.uri) { Media3PlaybackEngine(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    // The audio session id is 0 until the service MediaController connects, then becomes the
    // real id. Rebuild the VolumeController when it arrives so the LoudnessEnhancer binds to
    // the live session; the old instance is released by the DisposableEffect below.
    val audioSessionId = state.audioSessionId
    val volumeController = remember(audioSessionId) { VolumeController(context, audioSessionId) }

    // User-initiated exit: stop the service player, then navigate back. Auto-advance does NOT
    // use this (it must keep the player alive). Home press keeps playback alive too.
    val exitToLibrary = remember(engine, onBack) {
        {
            engine.stop()
            onBack()
        }
    }

    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(context))
    val resolved by playerViewModel.resolved.collectAsStateWithLifecycle()
    var resumeApplied by remember(item.uri) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volumeFactor by remember { mutableFloatStateOf(volumeController.currentFactor()) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureSeq by remember { mutableIntStateOf(0) }
    var speedBoostActive by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }

    // P1.E-2: Kids Lock + per-file orientation override.
    var locked by remember(item.uri) { mutableStateOf(false) }
    var orientationMode by remember(item.uri) { mutableStateOf(OrientationMode.AUTO) }
    val keyGuard = remember(activity) { activity as? HardwareKeyGuard }

    // P1.F-2: Picture-in-Picture. The Activity implements PipController; PiP entry is API 26+.
    val pipController = remember(activity) { activity as? PipController }
    val inPip = pipController?.pipMode?.value ?: false
    val pipSupported = pipAvailable(Build.VERSION.SDK_INT, settingEnabled = true)

    // Keep PiP params current (aspect) and auto-enter-on-home enabled while actually playing.
    LaunchedEffect(state.isPlaying, state.videoAspectRatio, pipSupported) {
        if (pipSupported) {
            val (n, d) = clampPipAspect(state.videoAspectRatio)
            pipController?.setAutoEnterPip(enabled = state.isPlaying, aspectNum = n, aspectDen = d)
        }
    }
    // Entering PiP hides the controls; nothing should overlay the floating window.
    LaunchedEffect(inPip) { if (inPip) controlsVisible = false }

    // A-B repeat state (resets when the media item changes)
    var abLoop by remember(item.uri) { mutableStateOf(AbLoop()) }

    // Sleep timer state
    var sleepDeadlineMs by remember { mutableStateOf<Long?>(null) }
    var sleepAtEndOfVideo by remember { mutableStateOf(false) }
    val sleepActive = sleepDeadlineMs != null || sleepAtEndOfVideo

    BackHandler { if (!locked) exitToLibrary() }

    // Request POST_NOTIFICATIONS (API 33+) so the media notification can show. Playback works
    // even if denied, so the result is ignored.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored: playback works without it */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Apply the per-file orientation. Keyed on item.uri (NOT just resolved): on auto-advance
    // PlayerScreen is reused, and `resolved` is a StateFlow that dedupes equal values, so two
    // files with identical resolved settings would skip this and leak the previous file's forced
    // orientation. Re-keying on item.uri guarantees a re-evaluation per file; a file with no saved
    // override applies UNSPECIFIED, releasing any prior lock (including a manual one).
    LaunchedEffect(item.uri, resolved) {
        val r = resolved
        orientationMode = if (r != null) orientationModeFromActivityInfo(r.orientation) else OrientationMode.AUTO
        activity?.requestedOrientation = r?.orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Kids Lock: while locked, ask the Activity to swallow volume/mute hardware keys.
    LaunchedEffect(locked) { keyGuard?.setHardwareKeysBlocked(locked) }
    DisposableEffect(keyGuard) { onDispose { keyGuard?.setHardwareKeysBlocked(false) } }

    LaunchedEffect(item.uri) {
        playerViewModel.load(item.uri)
    }
    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
    }
    // Apply resolved settings once the engine is READY and start playing. We wait for READY
    // (not BUFFERING) because the resume seek only lands reliably after the timeline is
    // established — seeking during BUFFERING drops the position (verified on device). This
    // never strands play(): the player stays in READY until we call play() (playWhenReady is
    // false), and the effect re-keys on `resolved`, so a late-arriving resolved is caught.
    LaunchedEffect(state.status, resolved) {
        val r = resolved
        if (!resumeApplied && r != null && state.status == PlayerStatus.READY) {
            if (r.startPositionMs > 0) engine.seekTo(r.startPositionMs)
            engine.setSpeed(r.speed)
            aspectMode = runCatching { AspectMode.valueOf(r.aspectMode) }.getOrDefault(AspectMode.FIT)
            resumeApplied = true
            engine.play()
        }
    }

    DisposableEffect(volumeController) {
        onDispose { volumeController.release() }
    }

    // Auto-hide controls after inactivity, only while playing.
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Auto-hide the transient gesture overlay shortly after the last gesture event.
    LaunchedEffect(gestureSeq) {
        if (gestureLabel != null) {
            delay(GESTURE_OVERLAY_MS)
            gestureLabel = null
        }
    }

    // A-B repeat enforcement: when position reaches or passes B, seek back to A.
    LaunchedEffect(state.positionMs, abLoop) {
        abLoopTarget(state.positionMs, abLoop)?.let { engine.seekTo(it) }
    }

    // Sleep timer enforcement: poll once per second; pause when the deadline is reached.
    LaunchedEffect(sleepDeadlineMs) {
        val deadline = sleepDeadlineMs ?: return@LaunchedEffect
        while (!isSleepExpired(deadline, System.currentTimeMillis())) {
            delay(1_000L)
        }
        engine.pause()
        sleepDeadlineMs = null
    }

    // Auto-advance: when the video ends, either honour the sleep-at-end-of-video flag
    // (pause and clear it) or advance to the next file in the folder playlist.
    LaunchedEffect(state.status) {
        if (state.status == PlayerStatus.ENDED) {
            if (sleepAtEndOfVideo) {
                engine.pause()
                sleepAtEndOfVideo = false
            } else if (AUTO_ADVANCE_DEFAULT) {
                nextInFolder(playlist, item.uri)?.let(onAdvance)
            }
        }
    }

    val latestPositionMs by rememberUpdatedState(state.positionMs)
    val latestDurationMs by rememberUpdatedState(state.durationMs)
    val latestSpeed by rememberUpdatedState(state.speed)
    val latestAspect by rememberUpdatedState(aspectMode)
    val latestBoost by rememberUpdatedState(speedBoostActive)

    // Save current state: periodically while playing, and on STOP / dispose.
    // Reads the last *composed* values rather than engine.state.value, because
    // AndroidView.onRelease calls engine.release() (resetting the StateFlow to zeros)
    // during disposal BEFORE this onDispose runs — reading live state here would
    // clobber the saved resume position with 0.
    fun saveNow() {
        if (!resumeApplied) return
        if (latestDurationMs <= 0L) return
        val speedToSave = if (latestBoost) 1f else latestSpeed
        playerViewModel.persist(
            mediaUri = item.uri,
            positionMs = latestPositionMs,
            durationMs = latestDurationMs,
            speed = speedToSave,
            aspectMode = latestAspect.name,
        )
    }

    LaunchedEffect(state.isPlaying, resumeApplied) {
        if (state.isPlaying && resumeApplied) {
            while (true) {
                delay(5_000L)
                saveNow()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveNow()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    },
                    onDoubleTap = { offset ->
                        val zone = resolveTapZone(offset.x, size.width.toFloat())
                        val snapshot = engine.state.value
                        when (doubleTapAction(zone)) {
                            DoubleTapAction.SEEK_BACKWARD ->
                                engine.seekTo(seekTarget(snapshot.positionMs, -SKIP_MS, snapshot.durationMs))
                            DoubleTapAction.SEEK_FORWARD ->
                                engine.seekTo(seekTarget(snapshot.positionMs, SKIP_MS, snapshot.durationMs))
                            DoubleTapAction.PLAY_PAUSE ->
                                if (snapshot.isPlaying) engine.pause() else engine.play()
                        }
                        controlsVisible = true
                        interactionTick++
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var side = VerticalSide.BRIGHTNESS
                detectVerticalDragGestures(
                    onDragStart = { offset -> side = verticalSide(offset.x, size.width.toFloat()) },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val h = size.height.toFloat()
                        when (side) {
                            VerticalSide.BRIGHTNESS -> {
                                brightness = applyBrightness(brightness, dragAmount, h)
                                activity?.let { setWindowBrightness(it, brightness) }
                                gestureLabel = "Brightness ${(brightness * 100).roundToInt()}%"
                            }
                            VerticalSide.VOLUME -> {
                                volumeFactor = applyVolumeFactor(volumeFactor, dragAmount, h)
                                volumeController.setFactor(volumeFactor)
                                gestureLabel = "Volume ${(volumeFactor * 100).roundToInt()}%"
                            }
                        }
                        gestureSeq++
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var totalDx = 0f
                var startPos = 0L
                var dur = 0L
                var target = 0L
                detectHorizontalDragGestures(
                    onDragStart = {
                        val s = engine.state.value
                        totalDx = 0f
                        startPos = s.positionMs
                        dur = s.durationMs
                        target = startPos
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDx += dragAmount
                        target = seekTarget(startPos, horizontalSeekDeltaMs(totalDx, size.width.toFloat()), dur)
                        val arrow = if (target >= startPos) "»" else "«"
                        gestureLabel = "$arrow ${formatDuration(target)}"
                        gestureSeq++
                    },
                    onDragEnd = {
                        engine.seekTo(target)
                        interactionTick++
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (awaitLongPressOrCancellation(down.id) != null) {
                        val previousSpeed = engine.state.value.speed
                        engine.setSpeed(BOOST_SPEED)
                        speedBoostActive = true
                        waitForUpOrCancellation()
                        engine.setSpeed(previousSpeed)
                        speedBoostActive = false
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).also { view ->
                    view.useController = false
                    view.setBackgroundColor(android.graphics.Color.BLACK)
                    engine.attachToView(view)
                }
            },
            update = { view ->
                // FIT/ZOOM use the video's intrinsic ratio (from the engine, reactive via state);
                // the named ratios force a fixed-ratio letterboxed frame. Restoring the intrinsic
                // ratio explicitly is what lets a ratio→FIT switch render correctly even when the
                // video started in a named-ratio mode.
                val cf = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                val natural = state.videoAspectRatio
                when (aspectMode) {
                    AspectMode.FIT -> {
                        if (natural > 0f) cf?.setAspectRatio(natural)
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    AspectMode.FILL -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> {
                        if (natural > 0f) cf?.setAspectRatio(natural)
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    AspectMode.RATIO_16_9 -> {
                        cf?.setAspectRatio(16f / 9f)
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    AspectMode.RATIO_4_3 -> {
                        cf?.setAspectRatio(4f / 3f)
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            },
            onRelease = { view ->
                view.player = null
                engine.release()
            },
        )

        AnimatedVisibility(visible = controlsVisible && !inPip) {
            PlayerControls(
                state = state,
                aspectLabel = aspectMode.displayLabel(),
                onCycleAspect = {
                    aspectMode = nextAspectMode(aspectMode)
                    gestureLabel = aspectMode.displayLabel()
                    gestureSeq++
                    interactionTick++
                },
                onPlayPause = {
                    if (state.isPlaying) engine.pause() else engine.play()
                    interactionTick++
                },
                onSeekTo = { target ->
                    engine.seekTo(target)
                    interactionTick++
                },
                onBack = exitToLibrary,
                currentSpeed = state.speed,
                onSetSpeed = { speed ->
                    engine.setSpeed(clampSpeed(speed))
                    interactionTick++
                },
                onFrameStep = { delta ->
                    val s = engine.state.value
                    engine.pause()
                    engine.seekTo(seekTarget(s.positionMs, delta, s.durationMs))
                    interactionTick++
                },
                abLoop = abLoop,
                onToggleAb = {
                    abLoop = when {
                        abLoop.startMs == null -> abLoop.copy(startMs = engine.state.value.positionMs)
                        abLoop.endMs == null -> abLoop.copy(endMs = engine.state.value.positionMs)
                        else -> AbLoop()
                    }
                    interactionTick++
                },
                sleepActive = sleepActive,
                onPickSleep = { option ->
                    when (option) {
                        SleepOption.OFF -> {
                            sleepDeadlineMs = null
                            sleepAtEndOfVideo = false
                        }
                        SleepOption.END_OF_VIDEO -> {
                            sleepAtEndOfVideo = true
                            sleepDeadlineMs = null
                        }
                        else -> {
                            sleepAtEndOfVideo = false
                            sleepDeadlineMs = System.currentTimeMillis() + option.minutes!! * 60_000L
                        }
                    }
                    interactionTick++
                },
                onLock = { locked = true; controlsVisible = false },
                orientationLabel = orientationMode.shortLabel(),
                onCycleOrientation = {
                    orientationMode = nextOrientationMode(orientationMode)
                    val ai = orientationMode.toActivityInfo()
                    activity?.requestedOrientation = ai
                    playerViewModel.persistOrientation(item.uri, ai)
                    interactionTick++
                },
                pipSupported = pipSupported,
                onEnterPip = {
                    val (n, d) = clampPipAspect(state.videoAspectRatio)
                    pipController?.enterPip(n, d)
                    interactionTick++
                },
            )
        }

        if (!inPip) {
            gestureLabel?.let { GestureOverlay(label = it) }
            if (speedBoostActive) GestureOverlay(label = "${BOOST_SPEED.toInt()}×")
        }

        if (locked && !inPip) {
            var hintVisible by remember { mutableStateOf(true) }
            var holdProgress by remember { mutableFloatStateOf(0f) }
            // While a hold is in progress, keep the affordance on-screen so the 3s unlock
            // hold can't be cut short by the hint's auto-hide (both are LOCK/UNLOCK = 3s).
            var holdActive by remember { mutableStateOf(false) }
            LaunchedEffect(hintVisible, holdActive) {
                if (hintVisible && !holdActive) { delay(LOCK_HINT_VISIBLE_MS); hintVisible = false }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { hintVisible = true }) },
            ) {
                if (hintVisible) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        holdActive = true
                                        holdProgress = 0f
                                        val unlocked = coroutineScope {
                                            val anim = Animatable(0f)
                                            val job = launch {
                                                anim.animateTo(
                                                    1f,
                                                    tween(UNLOCK_HOLD_MS.toInt(), easing = LinearEasing),
                                                ) { holdProgress = value }
                                            }
                                            val releasedEarly =
                                                withTimeoutOrNull(UNLOCK_HOLD_MS) { tryAwaitRelease() } != null
                                            job.cancel()
                                            !releasedEarly
                                        }
                                        holdProgress = 0f
                                        holdActive = false
                                        if (unlocked) locked = false
                                    },
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (holdProgress > 0f) {
                                CircularProgressIndicator(
                                    progress = { holdProgress },
                                    modifier = Modifier.size(72.dp),
                                    color = Color.White,
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Locked",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Text("Hold to unlock", color = Color.White)
                    }
                }
            }
        }
    }
}

/** Unwraps an [Activity] from a (possibly wrapped) Compose [Context]. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Applies a 0..1 [value] as the window's screen brightness override. */
private fun setWindowBrightness(activity: Activity, value: Float) {
    activity.window.attributes = activity.window.attributes.apply {
        screenBrightness = value.coerceIn(0f, 1f)
    }
}
