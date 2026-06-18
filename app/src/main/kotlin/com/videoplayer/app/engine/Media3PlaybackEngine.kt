package com.videoplayer.app.engine

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.videoplayer.app.playback.EXTRA_AUDIO_SESSION_ID
import com.videoplayer.app.playback.PlaybackService
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
 * Pixel-corrected display aspect ratio (width / height) for a video frame, or 0 when unknown
 * (a zero dimension). [pixelWidthHeightRatio] accounts for anamorphic / non-square pixels.
 */
fun videoAspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float): Float =
    if (width == 0 || height == 0) 0f else width * pixelWidthHeightRatio / height

/**
 * Media3 implementation of [PlaybackEngine].
 *
 * Playback is owned by [PlaybackService]; this engine connects to it via a
 * [MediaController] so audio keeps playing when the activity backgrounds. The
 * controller IS a [Player], so the existing [Player.Listener] body, position
 * poller, and helpers ([exoStateToStatus], [videoAspectRatio]) apply unchanged.
 *
 * Must be created and used on the main thread — the controller's listener fires
 * on the app main looper, which is where state updates happen. Connection is
 * async (a [ListenableFuture]); commands issued before connect are queued and
 * replayed when the controller resolves.
 */
@UnstableApi
class Media3PlaybackEngine(context: Context) : PlaybackEngine {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState(engine = EngineType.MEDIA3))
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var positionJob: Job? = null

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var attachedView: PlayerView? = null
    private var released = false

    /** Service player's audio session id, used to attach a LoudnessEnhancer for >100% volume. */
    val audioSessionId: Int get() = _state.value.audioSessionId

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            _state.update {
                it.copy(
                    status = exoStateToStatus(playbackState),
                    durationMs = c.duration.coerceAtLeast(0),
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val ratio = videoAspectRatio(
                videoSize.width,
                videoSize.height,
                videoSize.pixelWidthHeightRatio,
            )
            _state.update { it.copy(videoAspectRatio = ratio) }
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) return@addListener
            val c = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(listener)
            attachedView?.player = c
            seedStateFromController(c)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
            if (c.isPlaying) startPositionUpdates()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun seedStateFromController(c: MediaController) {
        val sessionId = c.sessionExtras.getInt(EXTRA_AUDIO_SESSION_ID, 0)
        _state.update {
            it.copy(
                status = exoStateToStatus(c.playbackState),
                isPlaying = c.isPlaying,
                durationMs = c.duration.coerceAtLeast(0),
                speed = c.playbackParameters.speed,
                audioSessionId = sessionId,
            )
        }
    }

    /** Run [block] on the controller now, or queue it to replay once connected. */
    private inline fun withController(crossinline block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add { block(it) }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * Binds the service-owned player to a Media3 [PlayerView] so video renders.
     * The view is cached and attached when the controller resolves. Engine-specific
     * surface attachment lives here, in the app layer — the core [PlaybackEngine]
     * abstraction stays free of any UI/Media3 types.
     */
    fun attachToView(view: PlayerView) {
        attachedView = view
        controller?.let { view.player = it }
    }

    override fun setMediaUri(uri: String) = withController { c ->
        c.setMediaItem(MediaItem.fromUri(uri))
        c.prepare()
    }

    override fun play() = withController { it.play() }

    override fun pause() = withController { it.pause() }

    override fun seekTo(positionMs: Long) = withController { c ->
        val target = positionMs.coerceAtLeast(0)
        c.seekTo(target)
        _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
    }

    override fun setSpeed(speed: Float) = withController { c ->
        c.setPlaybackParameters(PlaybackParameters(speed))
        _state.update { it.copy(speed = speed) }
    }

    /** Stop + clear the service player. Used when exiting the player to the library. */
    fun stop() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    override fun release() {
        released = true
        stopPositionUpdates()
        scope.cancel()
        controller?.removeListener(listener)
        attachedView?.player = null
        attachedView = null
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _state.value = PlaybackState()
    }
}
