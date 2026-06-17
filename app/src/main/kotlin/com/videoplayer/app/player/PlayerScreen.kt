package com.videoplayer.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.videoplayer.app.engine.Media3PlaybackEngine
import com.videoplayer.app.player.controls.DoubleTapAction
import com.videoplayer.app.player.controls.SKIP_MS
import com.videoplayer.app.player.controls.doubleTapAction
import com.videoplayer.app.player.controls.resolveTapZone
import com.videoplayer.app.player.controls.seekTarget
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.delay

private const val AUTO_HIDE_MS = 3_000L

/**
 * Full-screen playback for a single [MediaItem]. Owns a [Media3PlaybackEngine],
 * renders a custom Compose control overlay on top of the video surface, and
 * handles tap gestures: single-tap toggles the controls, double-tap seeks ±10s
 * on the sides or play/pauses in the center. Controls auto-hide after 3s while
 * playing. Tears down cleanly (clears the view's player, then releases).
 */
@Composable
fun PlayerScreen(
    item: MediaItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember(item.uri) { Media3PlaybackEngine(context) }
    val state by engine.state.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onBack)

    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
        engine.play()
    }

    // Auto-hide controls after a period of no interaction, but only while playing.
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
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
            onRelease = { view ->
                view.player = null
                engine.release()
            },
        )

        AnimatedVisibility(visible = controlsVisible) {
            PlayerControls(
                state = state,
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
    }
}
