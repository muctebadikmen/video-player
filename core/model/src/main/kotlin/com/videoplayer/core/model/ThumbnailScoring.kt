// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

/** Sample points (fractions of duration) used to choose a non-black auto-default frame. */
val THUMBNAIL_SAMPLE_FRACTIONS: List<Float> = listOf(0.15f, 0.30f, 0.50f, 0.70f, 0.85f)

/** Per-frame brightness/detail summary. [avgLuminance] and [variance] are over luma in 0..1. */
data class FrameStats(val avgLuminance: Double, val variance: Double)

/**
 * Mean and variance of perceptual luma (Rec. 601) for a block of ARGB pixels (as produced by
 * `Bitmap.getPixels`). Pure integer/double math so it is JVM-unit-testable with no Android types.
 */
fun luminanceStats(pixels: IntArray): FrameStats {
    if (pixels.isEmpty()) return FrameStats(0.0, 0.0)
    var sum = 0.0
    var sumSq = 0.0
    for (p in pixels) {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        sum += luma
        sumSq += luma * luma
    }
    val n = pixels.size
    val mean = sum / n
    val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
    return FrameStats(mean, variance)
}

/**
 * Heuristic desirability of a candidate thumbnail frame: rewards detail (variance), penalizes
 * near-black, blown-out, and flat/uniform frames. Higher is better. Tuned conservatively so a
 * legitimately dark-but-detailed frame still beats a pure-black one (manual override always wins).
 */
fun frameScore(stats: FrameStats): Double {
    val luma = stats.avgLuminance
    val detail = stats.variance
    val brightnessPenalty = when {
        luma < 0.06 -> 1.0   // essentially black
        luma < 0.12 -> 0.5   // very dark
        luma > 0.96 -> 0.6   // blown out
        else -> 0.0
    }
    val flatnessPenalty = if (detail < 0.002) 0.4 else 0.0
    return detail * 3.0 - brightnessPenalty - flatnessPenalty
}

/** Index of the highest-scoring frame; ties resolve to the earliest index. */
fun pickBestFrame(candidates: List<FrameStats>): Int {
    require(candidates.isNotEmpty()) { "candidates must not be empty" }
    var bestIdx = 0
    var best = Double.NEGATIVE_INFINITY
    candidates.forEachIndexed { i, s ->
        val score = frameScore(s)
        if (score > best) { best = score; bestIdx = i }
    }
    return bestIdx
}
