package com.videoplayer.app.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.videoplayer.core.playback.shouldStopOnTaskRemoved

/** Key under which the player's audio session id is published in the session extras. */
const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"

/** Custom session command: toggle ExoPlayer's pause-at-end-of-media-items behavior. */
const val CMD_SET_PAUSE_AT_END = "com.videoplayer.app.SET_PAUSE_AT_END"

/** Boolean arg for [CMD_SET_PAUSE_AT_END]. */
const val ARG_PAUSE_AT_END_ENABLED = "enabled"

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioSessionId = Util.generateAudioSessionIdV21(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioSessionId(audioSessionId)
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
        val extras = Bundle().apply { putInt(EXTRA_AUDIO_SESSION_ID, audioSessionId) }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionExtras(extras)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ConnectionResult {
                    val sessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_SET_PAUSE_AT_END, Bundle.EMPTY))
                        .build()
                    return ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_SET_PAUSE_AT_END) {
                        val enabled = args.getBoolean(ARG_PAUSE_AT_END_ENABLED, false)
                        (session.player as? ExoPlayer)?.pauseAtEndOfMediaItems = enabled
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            shouldStopOnTaskRemoved(player.playWhenReady, player.mediaItemCount)
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
