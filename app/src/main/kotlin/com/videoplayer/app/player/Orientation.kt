package com.videoplayer.app.player

import android.content.pm.ActivityInfo
import com.videoplayer.core.playback.OrientationMode

fun OrientationMode.toActivityInfo(): Int = when (this) {
    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    OrientationMode.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
}

fun orientationModeFromActivityInfo(value: Int?): OrientationMode = when (value) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> OrientationMode.PORTRAIT
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> OrientationMode.LANDSCAPE
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> OrientationMode.REVERSE_LANDSCAPE
    else -> OrientationMode.AUTO
}

fun OrientationMode.shortLabel(): String = when (this) {
    OrientationMode.AUTO -> "Auto"
    OrientationMode.PORTRAIT -> "Port"
    OrientationMode.LANDSCAPE -> "Land"
    OrientationMode.REVERSE_LANDSCAPE -> "Rev"
}
