// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import com.videoplayer.app.player.gestures.boostSpeedForPointers
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_TWO
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_BOTTOM_PADDING
import com.videoplayer.app.player.gestures.formatSpeedLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import com.videoplayer.app.engine.Media3PlaybackEngine
import com.videoplayer.app.player.controls.DoubleTapAction
import com.videoplayer.app.player.subtitle.CueOverlay
import com.videoplayer.app.player.subtitle.SubtitleStyle
import com.videoplayer.app.player.subtitle.captionStyleCompatFor
import com.videoplayer.app.player.subtitle.subtitleStyleSpec
import com.videoplayer.app.player.subtitle.SiblingSubtitleScanner
import com.videoplayer.app.player.subtitle.SubtitleLoader
import com.videoplayer.app.player.subtitle.SubtitleOption
import com.videoplayer.app.player.subtitle.SubtitleSearchSheet
import com.videoplayer.app.player.subtitle.SubtitleSearchViewModel
import com.videoplayer.app.player.controls.SKIP_MS
import com.videoplayer.app.player.controls.doubleTapAction
import com.videoplayer.app.player.controls.resolveTapZone
import com.videoplayer.app.player.controls.seekTarget
import com.videoplayer.app.player.gestures.AspectMode
import com.videoplayer.app.player.gestures.VerticalSide
import com.videoplayer.app.player.gestures.applyBrightness
import com.videoplayer.app.player.gestures.applySubtitleBottomPadding
import com.videoplayer.app.player.gestures.applySubtitleSize
import com.videoplayer.app.player.gestures.applyVolumeFactor
import com.videoplayer.app.player.gestures.displayLabel
import com.videoplayer.app.player.gestures.horizontalSeekDeltaMs
import com.videoplayer.app.player.gestures.nextAspectMode
import com.videoplayer.app.player.gestures.systemBrightnessFraction
import com.videoplayer.app.player.gestures.verticalSide
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration
import com.videoplayer.core.playback.AbLoop
import com.videoplayer.core.playback.FRAME_STEP_MS
import com.videoplayer.core.playback.SubtitleCue
import com.videoplayer.core.playback.SubtitleSelection
import com.videoplayer.core.playback.activeCueText
import com.videoplayer.core.playback.twoPointSync
import com.videoplayer.core.playback.nudgeSubtitleOffset
import com.videoplayer.core.playback.parseSubtitleToken
import com.videoplayer.core.playback.subtitleMemoryToken
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
import com.videoplayer.core.playback.shouldPlayInBackground
import com.videoplayer.core.playback.startIndexFor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val AUTO_HIDE_MS = 3_000L
private const val GESTURE_OVERLAY_MS = 800L

/**
 * Full-screen playback for a folder [playlist]. Owns a single per-session
 * [Media3PlaybackEngine] holding the whole folder as a native ExoPlayer playlist
 * (so next/previous and auto-advance are native and survive backgrounding). The
 * displayed item is derived from the engine's current media index; per-file
 * effects (resume/speed/aspect/orientation) re-key on the current item's uri.
 * Renders a custom Compose control overlay and handles gestures: tap toggles
 * controls; double-tap seeks ±10s (sides) or play/pauses (center); left-half
 * vertical drag changes brightness, right-half changes volume (to 200%). Each
 * gesture shows a transient [GestureOverlay].
 */
@Composable
fun PlayerScreen(
    playlist: List<MediaItem>,
    startUri: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Caller (VideoPlayerApp) always passes a non-empty playlist; this guard is the very
    // first statement so no Compose hooks run before it (keeps hook order stable) and lets
    // currentItem stay non-null below.
    if (playlist.isEmpty()) return
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val engine = remember { Media3PlaybackEngine(context) }
    val settingsRepo = remember(context) { SettingsRepository(context.applicationContext.settingsDataStore) }
    val scope = rememberCoroutineScope()
    val backgroundEnabled by settingsRepo.backgroundPlaybackEnabled.collectAsStateWithLifecycle(initialValue = true)
    val holdSpeedOne by settingsRepo.holdSpeedOneFinger.collectAsStateWithLifecycle(initialValue = DEFAULT_HOLD_SPEED_ONE)
    val holdSpeedTwo by settingsRepo.holdSpeedTwoFinger.collectAsStateWithLifecycle(initialValue = DEFAULT_HOLD_SPEED_TWO)
    val holdOneState = rememberUpdatedState(holdSpeedOne)
    val holdTwoState = rememberUpdatedState(holdSpeedTwo)
    val subtitleStylePref by settingsRepo.subtitleStyle.collectAsStateWithLifecycle(initialValue = SubtitleStyle.OUTLINE)
    var subtitleSizeFraction by remember { mutableFloatStateOf(DEFAULT_SUBTITLE_SIZE_FRACTION) }
    var subtitleBottomPadding by remember { mutableFloatStateOf(DEFAULT_SUBTITLE_BOTTOM_PADDING) }
    // Seed the local (drag-mutable) state from prefs once they emit.
    val sizePref by settingsRepo.subtitleSizeFraction.collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_SIZE_FRACTION)
    val posPref by settingsRepo.subtitleBottomPaddingFraction.collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_BOTTOM_PADDING)
    LaunchedEffect(sizePref) { subtitleSizeFraction = sizePref }
    LaunchedEffect(posPref) { subtitleBottomPadding = posPref }
    val state by engine.state.collectAsStateWithLifecycle()
    val startIndex = remember(playlist, startUri) { startIndexFor(playlist.map { it.uri }, startUri) }
    val currentItem = playlist.getOrElse(state.currentMediaIndex) {
        playlist.getOrElse(startIndex) { playlist.first() }
    }
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
    var resumeApplied by remember(currentItem.uri) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // Seed the gesture state from the real system brightness so the HUD baseline is accurate and
    // the first swipe doesn't jump the screen. The 3-arg getInt with default -1 never throws and
    // needs no permission; the window keeps system brightness until the first drag overrides it.
    val initialBrightness = remember {
        systemBrightnessFraction(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
    }
    var brightness by remember { mutableFloatStateOf(initialBrightness) }
    var volumeFactor by remember { mutableFloatStateOf(volumeController.currentFactor()) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureSeq by remember { mutableIntStateOf(0) }
    var speedBoostActive by remember { mutableStateOf(false) }
    var speedBoostLabel by remember { mutableStateOf("") }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }

    // P1.E-2: Kids Lock + per-file orientation override.
    var locked by remember(currentItem.uri) { mutableStateOf(false) }
    var orientationMode by remember(currentItem.uri) { mutableStateOf(OrientationMode.AUTO) }
    val keyGuard = remember(activity) { activity as? HardwareKeyGuard }

    // P1.F-2: Picture-in-Picture. The Activity implements PipController; PiP entry is API 26+.
    val pipController = remember(activity) { activity as? PipController }
    val inPip = pipController?.pipMode?.value ?: false
    val pipSupported = pipAvailable(Build.VERSION.SDK_INT, settingEnabled = shouldPlayInBackground(backgroundEnabled))

    // Keep PiP params current (aspect) and auto-enter-on-home enabled while actually playing.
    LaunchedEffect(state.isPlaying, state.videoAspectRatio, pipSupported) {
        if (pipSupported) {
            val (n, d) = clampPipAspect(state.videoAspectRatio)
            pipController?.setAutoEnterPip(enabled = state.isPlaying, aspectNum = n, aspectDen = d)
        } else {
            // Setting OFF (or PiP unsupported): actively cancel any previously-registered auto-enter.
            pipController?.setAutoEnterPip(enabled = false, aspectNum = 16, aspectDen = 9)
        }
    }
    // Entering PiP hides the controls; nothing should overlay the floating window.
    LaunchedEffect(inPip) { if (inPip) controlsVisible = false }

    // A-B repeat state (resets when the media item changes)
    var abLoop by remember(currentItem.uri) { mutableStateOf(AbLoop()) }

    // Subtitle state (re-keyed on currentItem.uri so a new file starts with no subtitle)
    var siblingSubtitles by remember(currentItem.uri) { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var externalSubtitles by remember(currentItem.uri) { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var selectedSubtitleUri by remember(currentItem.uri) { mutableStateOf<String?>(null) }
    var subtitleOffsetMs by remember(currentItem.uri) { mutableStateOf(0L) }
    var subtitleCues by remember(currentItem.uri) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    // Restore guard: flips to true once the remembered subtitle for this file has been applied.
    // Until then, saveNow() re-writes the resolved (saved) token so early saves can't wipe it.
    var subtitleRestored by remember(currentItem.uri) { mutableStateOf(false) }
    val subtitleOptions = remember(siblingSubtitles, externalSubtitles) {
        (siblingSubtitles + externalSubtitles).distinctBy { it.uri }
    }
    val subtitleToken = subtitleMemoryToken(state.selectedTextTrackId, selectedSubtitleUri)

    var subtitleRate by remember(currentItem.uri) { mutableStateOf(1.0f) }
    // Two-point precise-sync capture (UI-driven). When a "Mark" is taken we store the cue's original
    // start (the cue active at that instant, or the nearest upcoming cue) and the real playback time.
    var twoPointPhase by remember(currentItem.uri) { mutableStateOf(TwoPointPhase.IDLE) }
    var twoPointFirstOrig by remember(currentItem.uri) { mutableStateOf(0L) }
    var twoPointFirstWant by remember(currentItem.uri) { mutableStateOf(0L) }

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

    // SAF subtitle file picker: takes a persistable read permission so the chosen subtitle
    // survives app restarts without re-prompting (G-4 will restore it from memory).
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = subtitleDisplayName(context, uri) ?: uri.lastPathSegment ?: "Subtitle"
            val option = SubtitleOption(uri.toString(), name)
            externalSubtitles = (externalSubtitles + option).distinctBy { it.uri }
            engine.selectEmbeddedTextTrack(null)
            selectedSubtitleUri = option.uri
        }
    }
    val searchViewModel: SubtitleSearchViewModel =
        viewModel(factory = SubtitleSearchViewModel.factory(context))
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    var showSearchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Apply the per-file orientation. Keyed on currentItem.uri (NOT just resolved): on a native
    // playlist transition currentItem.uri changes, and `resolved` is a StateFlow that dedupes equal
    // values, so two files with identical resolved settings would skip this and leak the previous
    // file's forced orientation. Re-keying on currentItem.uri guarantees a re-evaluation per file; a
    // file with no saved override applies UNSPECIFIED, releasing any prior lock (including a manual one).
    LaunchedEffect(currentItem.uri, resolved) {
        val r = resolved
        if (r != null && r.mediaUri != currentItem.uri) return@LaunchedEffect // ignore a stale (different-uri) result
        orientationMode = if (r != null) orientationModeFromActivityInfo(r.orientation) else OrientationMode.AUTO
        activity?.requestedOrientation = r?.orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Release the brightness override so system-controlled brightness resumes outside the player.
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }

    // Kids Lock: while locked, ask the Activity to swallow volume/mute hardware keys.
    LaunchedEffect(locked) { keyGuard?.setHardwareKeysBlocked(locked) }
    DisposableEffect(keyGuard) { onDispose { keyGuard?.setHardwareKeysBlocked(false) } }

    LaunchedEffect(currentItem.uri) {
        playerViewModel.load(currentItem.uri)
    }

    // Sibling scan: best-effort discovery of same-folder subtitle files via MediaStore.
    LaunchedEffect(currentItem.uri) {
        siblingSubtitles = SiblingSubtitleScanner.scan(
            context = context,
            videoFolderName = currentItem.folderPath.substringAfterLast('/'),
            videoFileName = currentItem.displayName,
        )
    }

    // Embedded text defaults to OFF on each file: offer-don't-auto-show, and clears any
    // text-disable/override left from a previous file's external subtitle (track-selection
    // params persist across ExoPlayer items). The user enables an embedded track via the CC menu.
    LaunchedEffect(currentItem.uri) {
        engine.selectEmbeddedTextTrack(null)
    }

    // Restore the remembered subtitle selection + offset once resolved settings (and, for an
    // embedded track, the track list) are available. Guarded so it runs once per file.
    LaunchedEffect(currentItem.uri, resolved, state.textTracks) {
        val r = resolved ?: return@LaunchedEffect
        if (r.mediaUri != currentItem.uri) return@LaunchedEffect // ignore a stale (different-uri) result
        if (subtitleRestored) return@LaunchedEffect
        when (val sel = parseSubtitleToken(r.subtitleTrackId)) {
            is SubtitleSelection.Off -> subtitleRestored = true // default-off effect already disabled embedded
            is SubtitleSelection.External -> {
                val uri = sel.uri
                val name = subtitleDisplayName(context, Uri.parse(uri))
                    ?: uri.substringAfterLast('/').ifEmpty { "Subtitle" }
                externalSubtitles = (externalSubtitles + SubtitleOption(uri, name)).distinctBy { it.uri }
                subtitleOffsetMs = r.subtitleOffsetMs
                subtitleRate = r.subtitleRate
                engine.selectEmbeddedTextTrack(null)
                selectedSubtitleUri = uri
                subtitleRestored = true
            }
            is SubtitleSelection.Embedded -> {
                if (state.textTracks.any { it.id == sel.id }) {
                    selectedSubtitleUri = null
                    engine.selectEmbeddedTextTrack(sel.id)
                    subtitleRestored = true
                } else if (state.textTracks.isNotEmpty()) {
                    // Tracks have arrived but the remembered id is gone (unsupported/filtered out, or
                    // the file's track layout changed). Stop waiting: leave subtitles Off and mark
                    // restored so the persist effect un-gates instead of staying frozen all session.
                    subtitleRestored = true
                }
                // else: tracks not loaded yet — wait for state.textTracks to update (effect re-runs).
            }
        }
    }

    // Persist a subtitle change immediately (so it survives even if the user pauses and exits
    // before any periodic save). Mirrors persistOrientation. Skips the no-op fire right after
    // restore by comparing against the saved (resolved) values.
    LaunchedEffect(subtitleToken, subtitleOffsetMs, subtitleRate, subtitleRestored, currentItem.uri) {
        if (!subtitleRestored) return@LaunchedEffect
        val r = resolved
        if (subtitleToken != r?.subtitleTrackId ||
            subtitleOffsetMs != (r?.subtitleOffsetMs ?: 0L) ||
            subtitleRate != (r?.subtitleRate ?: 1.0f)
        ) {
            playerViewModel.persistSubtitle(currentItem.uri, subtitleToken, subtitleOffsetMs, subtitleRate)
        }
    }

    // Cue load: parse the selected subtitle file whenever the selection changes.
    LaunchedEffect(selectedSubtitleUri) {
        val uri = selectedSubtitleUri
        subtitleCues = if (uri == null) emptyList() else SubtitleLoader.load(context, uri)
    }

    LaunchedEffect(engine) {
        engine.setMediaPlaylist(playlist.map { it.uri }, startIndex)
    }
    // Apply resolved settings once the engine is READY and start playing. We wait for READY
    // (not BUFFERING) because the resume seek only lands reliably after the timeline is
    // established — seeking during BUFFERING drops the position (verified on device). This
    // never strands play(): the player stays in READY until we call play() (playWhenReady is
    // false), and the effect re-keys on `resolved`, so a late-arriving resolved is caught.
    LaunchedEffect(state.status, resolved) {
        val r = resolved
        if (r != null && r.mediaUri != currentItem.uri) return@LaunchedEffect // ignore a stale (different-uri) result
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

    // The duration sleep timer is wall-clock and spans auto-advance (unlike the per-file A–B loop,
    // which resets per media item).
    // Sleep timer enforcement: poll once per second; pause when the deadline is reached.
    LaunchedEffect(sleepDeadlineMs) {
        val deadline = sleepDeadlineMs ?: return@LaunchedEffect
        while (!isSleepExpired(deadline, System.currentTimeMillis())) {
            delay(1_000L)
        }
        engine.pause()
        sleepDeadlineMs = null
    }

    // Auto-advance is native (the ExoPlayer playlist advances itself, even in the background).
    // Sleep "at end of video" maps to ExoPlayer's pause-at-end-of-media-items: when armed, the
    // current clip plays to its end and pauses instead of advancing.
    LaunchedEffect(sleepAtEndOfVideo) {
        engine.setPauseAtEndOfMediaItems(sleepAtEndOfVideo)
    }

    val latestPositionMs by rememberUpdatedState(state.positionMs)
    val latestDurationMs by rememberUpdatedState(state.durationMs)
    val latestSpeed by rememberUpdatedState(state.speed)
    val latestAspect by rememberUpdatedState(aspectMode)
    val latestBoost by rememberUpdatedState(speedBoostActive)
    val latestCurrentUri by rememberUpdatedState(currentItem.uri)
    val latestBackgroundEnabled by rememberUpdatedState(backgroundEnabled)

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
            mediaUri = latestCurrentUri,
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
            if (event == Lifecycle.Event.ON_STOP) {
                saveNow()
                if (!latestBackgroundEnabled) engine.pause()
            }
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
                        try {
                            var pressed = currentEvent.changes.count { it.pressed }.coerceAtLeast(1)
                            var applied = boostSpeedForPointers(pressed, holdOneState.value, holdTwoState.value)
                            engine.setSpeed(applied)
                            speedBoostLabel = formatSpeedLabel(applied)
                            speedBoostActive = true
                            // Track finger-count changes live until every pointer lifts.
                            while (true) {
                                val event = awaitPointerEvent()
                                pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                val next = boostSpeedForPointers(pressed, holdOneState.value, holdTwoState.value)
                                if (next != applied) {
                                    applied = next
                                    engine.setSpeed(applied)
                                    speedBoostLabel = formatSpeedLabel(applied)
                                }
                            }
                        } finally {
                            // Always restore speed and clear the badge, even if the gesture
                            // coroutine is cancelled mid-hold (dispose/navigation/interruption).
                            engine.setSpeed(previousSpeed)
                            speedBoostActive = false
                        }
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
                view.subtitleView?.let { sv ->
                    if (subtitleStylePref == SubtitleStyle.SYSTEM) {
                        sv.setApplyEmbeddedStyles(true)
                        sv.setUserDefaultStyle()
                        sv.setUserDefaultTextSize()
                    } else {
                        sv.setApplyEmbeddedStyles(false)
                        sv.setStyle(captionStyleCompatFor(subtitleStyleSpec(subtitleStylePref)))
                        sv.setFractionalTextSize(subtitleSizeFraction)
                    }
                    sv.setBottomPaddingFraction(subtitleBottomPadding)
                }
            },
            onRelease = { view ->
                view.player = null
                engine.release()
            },
        )

        // Subtitle adjust layer: drag to move (pan.y), pinch to resize (zoom). One gesture
        // handler avoids two detectors racing. Placed BEFORE the controls below so the seek
        // bar/buttons (drawn on top) always win their own touches; only active while controls
        // are visible so it never competes with the hold-to-speed gesture on the bare surface.
        if (!inPip && controlsVisible) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f),
            ) {
                val hPx = constraints.maxHeight.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var adjusted = false
                                do {
                                    val event = awaitPointerEvent()
                                    // Two-finger only: single-finger drags fall through to the
                                    // brightness/volume/seek gestures on the surface below.
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        val panY = event.calculatePan().y
                                        var changed = false
                                        if (zoom != 1f) {
                                            subtitleSizeFraction = applySubtitleSize(subtitleSizeFraction, zoom)
                                            changed = true
                                        }
                                        if (panY != 0f) {
                                            subtitleBottomPadding =
                                                applySubtitleBottomPadding(subtitleBottomPadding, panY, hPx)
                                            changed = true
                                        }
                                        if (changed) {
                                            event.changes.forEach { it.consume() }
                                            adjusted = true
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (adjusted) {
                                    scope.launch {
                                        settingsRepo.setSubtitleSizeFraction(subtitleSizeFraction)
                                        settingsRepo.setSubtitleBottomPaddingFraction(subtitleBottomPadding)
                                    }
                                }
                            }
                        },
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                state = state,
                aspectLabel = aspectMode.displayLabel(),
                onCycleAspect = {
                    aspectMode = nextAspectMode(aspectMode)
                    // Persist immediately (like orientation/subtitle) so an interleaved
                    // orientation/subtitle write can't re-save the previous aspect, and so the
                    // choice survives even if the dispose-time saveNow() is a no-op (released engine).
                    playerViewModel.persistAspect(currentItem.uri, aspectMode.name)
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
                    playerViewModel.persistOrientation(currentItem.uri, ai)
                    interactionTick++
                },
                pipSupported = pipSupported,
                onEnterPip = {
                    val (n, d) = clampPipAspect(state.videoAspectRatio)
                    pipController?.enterPip(n, d)
                    interactionTick++
                },
                subtitleOptions = subtitleOptions,
                selectedSubtitleUri = selectedSubtitleUri,
                subtitleOffsetMs = subtitleOffsetMs,
                onSelectSubtitle = { uri ->
                    engine.selectEmbeddedTextTrack(null) // external/off disables embedded text
                    selectedSubtitleUri = uri
                },
                onLoadSubtitleFile = { subtitlePicker.launch(arrayOf("*/*")) },
                onSearchOnline = {
                    showSearchSheet = true
                    searchViewModel.search(context, currentItem.uri, currentItem.displayName)
                },
                onNudgeSubtitle = { delta -> subtitleOffsetMs = nudgeSubtitleOffset(subtitleOffsetMs, delta) },
                subtitleRate = subtitleRate,
                onAdjustRate = { delta ->
                    // Clamp to a sane band; 3-decimal granularity, identity stays reachable.
                    subtitleRate = (subtitleRate + delta).coerceIn(0.5f, 2.0f)
                },
                onResetRate = { subtitleRate = 1.0f },
                twoPointPhase = twoPointPhase,
                onStartTwoPoint = { twoPointPhase = TwoPointPhase.WAITING_FIRST },
                onCancelTwoPoint = { twoPointPhase = TwoPointPhase.IDLE },
                onMarkTwoPoint = {
                    val want = cueStartForMark(
                        subtitleCues, state.positionMs, subtitleOffsetMs, subtitleRate.toDouble(),
                    )
                    if (want != null) {
                        when (twoPointPhase) {
                            TwoPointPhase.WAITING_FIRST -> {
                                twoPointFirstOrig = state.positionMs
                                twoPointFirstWant = want
                                twoPointPhase = TwoPointPhase.WAITING_SECOND
                            }
                            TwoPointPhase.WAITING_SECOND -> {
                                val fit = twoPointSync(
                                    orig1 = twoPointFirstOrig, want1 = twoPointFirstWant,
                                    orig2 = state.positionMs, want2 = want,
                                )
                                subtitleRate = fit.rate.toFloat().coerceIn(0.5f, 2.0f)
                                subtitleOffsetMs = fit.offset
                                twoPointPhase = TwoPointPhase.IDLE
                            }
                            TwoPointPhase.IDLE -> { /* no-op: Mark is only shown while capturing */ }
                        }
                    }
                },
                textTracks = state.textTracks,
                selectedTextTrackId = state.selectedTextTrackId,
                onSelectEmbedded = { id ->
                    selectedSubtitleUri = null // embedded selection clears the custom overlay
                    engine.selectEmbeddedTextTrack(id)
                },
            )
        }

        if (!inPip) {
            gestureLabel?.let { GestureOverlay(label = it) }
            if (speedBoostActive) SpeedBadge(label = speedBoostLabel)
        }

        if (!inPip) {
            CueOverlay(
                text = activeCueText(subtitleCues, state.positionMs, subtitleOffsetMs, subtitleRate.toDouble()),
                style = subtitleStylePref,
                sizeFraction = subtitleSizeFraction,
                bottomPaddingFraction = subtitleBottomPadding,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }

        if (showSearchSheet && !inPip) {
            SubtitleSearchSheet(
                state = searchState,
                onDownload = { result ->
                    searchViewModel.download(context, result, currentItem.displayName) { savedUri ->
                        if (savedUri != null) {
                            // Mirror the SAF callback: register + select the downloaded subtitle.
                            val name = subtitleDisplayName(context, Uri.parse(savedUri))
                                ?: savedUri.substringAfterLast('/').ifEmpty { "Subtitle" }
                            val option = SubtitleOption(savedUri, name)
                            externalSubtitles = (externalSubtitles + option).distinctBy { it.uri }
                            engine.selectEmbeddedTextTrack(null)
                            selectedSubtitleUri = savedUri
                            showSearchSheet = false
                        }
                    }
                },
                onOpenSettings = onOpenSettings,
                onDismiss = { showSearchSheet = false },
            )
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

/**
 * The original file-start time (ms) of the cue the user is marking for two-point sync. Prefers the
 * cue currently on screen at [positionMs] under the active correction; if none is showing, falls back
 * to the next upcoming cue's start (so a tap slightly before a line still captures that line). Null
 * when there are no cues at/after the position.
 */
private fun cueStartForMark(
    cues: List<com.videoplayer.core.playback.SubtitleCue>,
    positionMs: Long,
    offsetMs: Long,
    rate: Double,
): Long? {
    val t = (positionMs * rate).toLong() + offsetMs
    cues.firstOrNull { t >= it.startMs && t < it.endMs }?.let { return it.startMs }
    return cues.filter { it.startMs >= t }.minByOrNull { it.startMs }?.startMs
}

/** Queries the display name for a SAF [Uri] via [OpenableColumns]. Returns null on any failure. */
private fun subtitleDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

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