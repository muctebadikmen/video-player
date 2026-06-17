package com.videoplayer.app.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videoplayer.core.playback.EngineType
import com.videoplayer.core.playback.PlaybackEngine
import com.videoplayer.core.playback.PlaybackState
import com.videoplayer.core.playback.PlayerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Must be created and used on the main thread — ExoPlayer's listener fires
 * on the app main looper, which is where state updates happen.
 */
class Media3PlaybackEngine(context: Context) : PlaybackEngine {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(PlaybackState(engine = EngineType.MEDIA3))
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    status = exoStateToStatus(playbackState),
                    durationMs = player.duration.coerceAtLeast(0),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
        })
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
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    override fun release() {
        player.release()
        _state.value = PlaybackState()
    }
}
