// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

import kotlin.math.roundToInt

/** Volume can be boosted to 200% of system max (1.0 = system max). */
const val MAX_VOLUME_FACTOR = 2f

/** A full-width horizontal drag scrubs this many ms. Tunable (sensitivity). */
const val SEEK_MS_PER_WIDTH = 90_000L

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

// --- Hold-to-speed (configurable one/two-finger) ---

const val HOLD_SPEED_MIN = 1.0f
const val HOLD_SPEED_MAX = 4.0f
const val DEFAULT_HOLD_SPEED_ONE = 2.0f
const val DEFAULT_HOLD_SPEED_TWO = 3.0f

/** Boost speed for the number of fingers currently held (>=2 fingers → two-finger speed). */
fun boostSpeedForPointers(pressedCount: Int, oneFinger: Float, twoFinger: Float): Float =
    if (pressedCount >= 2) twoFinger else oneFinger

/** True when a brightness/volume/seek drag must be ignored because hold-to-speed owns the pointers. */
fun shouldIgnoreDrag(speedBoostActive: Boolean): Boolean = speedBoostActive

/** Compact label for the speed badge: "2×", "2.5×" (one decimal, trailing .0 dropped). */
fun formatSpeedLabel(speed: Float): String {
    val rounded = (speed * 10f).roundToInt() / 10f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return "$text×"
}

fun clampHoldSpeed(speed: Float): Float = speed.coerceIn(HOLD_SPEED_MIN, HOLD_SPEED_MAX)

// --- Subtitle size & position (fractions of player height) ---

const val SUBTITLE_SIZE_MIN = 0.04f
const val SUBTITLE_SIZE_MAX = 0.10f
const val DEFAULT_SUBTITLE_SIZE_FRACTION = 0.0533f

const val SUBTITLE_POS_MIN = 0.02f
const val SUBTITLE_POS_MAX = 0.50f
const val DEFAULT_SUBTITLE_BOTTOM_PADDING = 0.08f

fun clampSubtitleSize(fraction: Float): Float = fraction.coerceIn(SUBTITLE_SIZE_MIN, SUBTITLE_SIZE_MAX)

fun clampSubtitleBottomPadding(fraction: Float): Float =
    fraction.coerceIn(SUBTITLE_POS_MIN, SUBTITLE_POS_MAX)

/** New bottom-padding fraction after a vertical drag; dragging **up** (negative dy) raises it. */
fun applySubtitleBottomPadding(current: Float, dragYpx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return clampSubtitleBottomPadding(current - dragYpx / heightPx)
}

/** New size fraction after a pinch; [zoom] > 1 grows the text. */
fun applySubtitleSize(current: Float, zoom: Float): Float = clampSubtitleSize(current * zoom)