// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

/** Volume can be boosted to 200% of system max (1.0 = system max). */
const val MAX_VOLUME_FACTOR = 2f

/** A full-width horizontal drag scrubs this many ms. Tunable (sensitivity). */
const val SEEK_MS_PER_WIDTH = 90_000L

/** Playback speed applied while the long-press is held. */
const val BOOST_SPEED = 2f

/** Which vertical gesture a touch controls, decided by screen half. */
enum class VerticalSide { BRIGHTNESS, VOLUME }

/** Left half of the surface controls brightness, right half controls volume. */
fun verticalSide(x: Float, width: Float): VerticalSide =
    if (width > 0f && x >= width / 2f) VerticalSide.VOLUME else VerticalSide.BRIGHTNESS

/**
 * Normalizes the Android system brightness setting (raw 0..[max]) into a 0f..1f fraction
 * for seeding the brightness gesture state on player entry. Returns [fallback] when the
 * setting is unknown (raw < 0); otherwise clamps to a small floor so the screen is never
 * driven fully dark by a seed value.
 */
fun systemBrightnessFraction(raw: Int, max: Int = 255, fallback: Float = 0.5f): Float =
    if (raw < 0) fallback else (raw.toFloat() / max).coerceIn(0.01f, 1f)

/** New brightness (0..1) after a vertical drag; dragging **up** (negative dy) raises it. */
fun applyBrightness(current: Float, dragYpx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current - dragYpx / heightPx).coerceIn(0f, 1f)
}

/** New volume factor (0..[MAX_VOLUME_FACTOR]) after a vertical drag; up raises it. */
fun applyVolumeFactor(current: Float, dragYpx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current - dragYpx / heightPx).coerceIn(0f, MAX_VOLUME_FACTOR)
}

/** Seek offset in ms for a horizontal drag; rightward (positive dx) seeks forward. */
fun horizontalSeekDeltaMs(dragXpx: Float, widthPx: Float): Long {
    if (widthPx <= 0f) return 0L
    return ((dragXpx / widthPx) * SEEK_MS_PER_WIDTH).toLong()
}