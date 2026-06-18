package com.videoplayer.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.videoplayer.app.player.gestures.horizontalSeekDeltaMs
import com.videoplayer.app.player.gestures.nextAspectMode
import com.videoplayer.app.player.gestures.verticalSide
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration
import com.videoplayer.core.playback.PlayerStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val AUTO_HIDE_MS = 3_000L
private const val GESTURE_OVERLAY_MS = 800L

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val engine = remember(item.uri) { Media3PlaybackEngine(context) }
    val volumeController = remember(engine) { VolumeController(context, engine.audioSessionId) }
    val state by engine.state.collectAsStateWithLifecycle()

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

    BackHandler(onBack = onBack)

    LaunchedEffect(item.uri) {
        playerViewModel.load(item.uri)
    }
    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
    }
    // Apply resolved settings once the engine is READY (duration known) and start playing.
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

    val latestAspect by rememberUpdatedState(aspectMode)
    val latestBoost by rememberUpdatedState(speedBoostActive)

    // Save current state: periodically while playing, and on STOP / dispose.
    fun saveNow() {
        if (!resumeApplied) return
        val s = engine.state.value
        val speedToSave = if (latestBoost) 1f else s.speed
        playerViewModel.persist(
            mediaUri = item.uri,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
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
            .pointerInput(Unit) {
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
            .pointerInput(Unit) {
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
            .pointerInput(Unit) {
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
            .pointerInput(Unit) {
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
                view.resizeMode = when (aspectMode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            onRelease = { view ->
                view.player = null
                engine.release()
            },
        )

        AnimatedVisibility(visible = controlsVisible) {
            PlayerControls(
                state = state,
                aspectLabel = aspectMode.name.lowercase().replaceFirstChar { it.uppercase() },
                onCycleAspect = {
                    aspectMode = nextAspectMode(aspectMode)
                    gestureLabel = aspectMode.name.lowercase().replaceFirstChar { it.uppercase() }
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
                onBack = onBack,
            )
        }

        gestureLabel?.let { GestureOverlay(label = it) }
        if (speedBoostActive) GestureOverlay(label = "${BOOST_SPEED.toInt()}×")
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
