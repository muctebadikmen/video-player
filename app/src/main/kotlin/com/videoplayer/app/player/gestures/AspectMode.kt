package com.videoplayer.app.player.gestures

/** How the video frame fills the surface. Maps to a Media3 PlayerView resize mode in the UI. */
enum class AspectMode { FIT, FILL, ZOOM }

/** Cycles FIT → FILL → ZOOM → FIT. */
fun nextAspectMode(current: AspectMode): AspectMode = when (current) {
    AspectMode.FIT -> AspectMode.FILL
    AspectMode.FILL -> AspectMode.ZOOM
    AspectMode.ZOOM -> AspectMode.FIT
}
