// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

/** How the video frame fills the surface. Maps to a Media3 PlayerView resize mode in the UI. */
enum class AspectMode { FIT, FILL, ZOOM, RATIO_16_9, RATIO_4_3 }

/** Cycles FIT → FILL → ZOOM → 16:9 → 4:3 → FIT. */
fun nextAspectMode(current: AspectMode): AspectMode = when (current) {
    AspectMode.FIT -> AspectMode.FILL
    AspectMode.FILL -> AspectMode.ZOOM
    AspectMode.ZOOM -> AspectMode.RATIO_16_9
    AspectMode.RATIO_16_9 -> AspectMode.RATIO_4_3
    AspectMode.RATIO_4_3 -> AspectMode.FIT
}

/** Short human label for the control overlay (e.g. "16:9" instead of the raw enum name). */
fun AspectMode.displayLabel(): String = when (this) {
    AspectMode.FIT -> "Fit"
    AspectMode.FILL -> "Fill"
    AspectMode.ZOOM -> "Zoom"
    AspectMode.RATIO_16_9 -> "16:9"
    AspectMode.RATIO_4_3 -> "4:3"
}