package com.videoplayer.app.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.videoplayer.core.playback.EngineType
import com.videoplayer.core.playback.PlaybackEngine
import com.videoplayer.core.playback.PlaybackState
import com.videoplayer.core.playback.PlayerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Interval between position samples while playing. */
private const val POSITION_POLL_MS = 250L

/**
 * Maps ExoPlayer/Player playback-state int constants to our [PlayerStatus] enum.
 * Pure function — no side effects, easy to unit-test without a real player.
 */
fun exoStateToStatus(playbackState: Int): PlayerStatus = when (playbackState) {
    Player.STATE_IDLE -> PlayerStatus.IDLE
    Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
    Player.STATE_READY -> PlayerStatus.READY
    Player.STATE_ENDED -> PlayerStatus.ENDED
    else -> PlayerStatus.IDLE
}

/**
 * Media3/ExoPlayer implementation of [PlaybackEngine].
 *
 * Must be created and used on the main thread — ExoPlayer's listener fires on
 * the app main looper, which is where state updates happen. While playing, a
 * coroutine samples `currentPosition` into [PlaybackState.positionMs] so the
 * custom control overlay can render a live scrubber. Seeks are keyframe-instant
 * ([SeekParameters.CLOSEST_SYNC]).
 */
class Media3PlaybackEngine(context: Context) : PlaybackEngine {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var positionJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState(engine = EngineType.MEDIA3))
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    it.copy(
                        status = exoStateToStatus(playbackState),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        })
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0)) }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * Binds this engine's player to a Media3 [PlayerView] so video renders.
     * Engine-specific surface attachment lives here, in the app layer — the
     * core [PlaybackEngine] abstraction stays free of any UI/Media3 types.
     */
    fun attachToView(view: PlayerView) {
        view.player = player
    }

    override fun setMediaUri(uri: String) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    override fun release() {
        stopPositionUpdates()
        scope.cancel()
        player.release()
        _state.value = PlaybackState()
    }
}
