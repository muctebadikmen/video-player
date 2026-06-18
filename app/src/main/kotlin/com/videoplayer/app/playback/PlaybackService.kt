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
import androidx.media3.session.MediaSessionService
import com.videoplayer.core.playback.shouldStopOnTaskRemoved

/** Key under which the player's audio session id is published in the session extras. */
const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"

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
