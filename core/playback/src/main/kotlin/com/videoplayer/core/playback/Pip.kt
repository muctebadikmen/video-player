package com.videoplayer.core.playback

import kotlin.math.roundToInt

/** Android rejects PiP aspect ratios outside ~[1/2.39, 2.39]; stay just inside to avoid float-edge throws. */
const val PIP_MIN_ASPECT: Float = 0.42f
const val PIP_MAX_ASPECT: Float = 2.38f

/** PiP entry is available on API 26+ and only when the user setting allows it. */
fun pipAvailable(apiLevel: Int, settingEnabled: Boolean): Boolean =
    settingEnabled && apiLevel >= 26

/**
 * Clamp a video aspect ratio to Android's allowed PiP range and return it as an
 * integer numerator/denominator pair (for `android.util.Rational`). Falls back to
 * 16:9 when the ratio is unknown (<= 0).
 */
fun clampPipAspect(ratio: Float): Pair<Int, Int> {
    if (ratio <= 0f) return 16 to 9
    val clamped = ratio.coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT)
    return (clamped * 10000).roundToInt() to 10000
}
