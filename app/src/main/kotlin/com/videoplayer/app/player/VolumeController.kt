// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import com.videoplayer.app.player.gestures.MAX_VOLUME_FACTOR
import kotlin.math.roundToInt

/** Target gain (millibels) applied at the top of the boost range (factor = 2.0). Tunable. */
private const val BOOST_MAX_MB = 800

/**
 * Controls playback loudness as a single 0..[MAX_VOLUME_FACTOR] factor:
 * 0..1 maps to the system music-stream volume; 1..2 keeps the stream at max and
 * adds gain via a [LoudnessEnhancer] bound to the player's audio session, giving
 * the spec's "up to 200%" boost.
 */
class VolumeController(context: Context, private val audioSessionId: Int) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private var enhancer: LoudnessEnhancer? = null

    /** Current factor from the system stream volume (boost not reflected; starts at ≤1). */
    fun currentFactor(): Float =
        if (maxStream == 0) 0f else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxStream

    fun setFactor(factor: Float) {
        val f = factor.coerceIn(0f, MAX_VOLUME_FACTOR)
        val streamVol = (f.coerceAtMost(1f) * maxStream).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamVol, 0)
        applyBoost((f - 1f).coerceAtLeast(0f))
    }

    private fun applyBoost(boost: Float) {
        if (boost <= 0f) {
            enhancer?.let { it.setTargetGain(0); it.enabled = false }
            return
        }
        val e = enhancer ?: runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            ?.also { enhancer = it } ?: return
        e.setTargetGain((boost * BOOST_MAX_MB).roundToInt())
        e.enabled = true
    }

    fun release() {
        enhancer?.release()
        enhancer = null
    }
}