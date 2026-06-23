// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import kotlinx.coroutines.flow.Flow

/** Configurable hold-to-speed values, separable for testing (mirrors GridSizePreferences). */
interface PlaybackGesturePreferences {
    val holdSpeedOneFinger: Flow<Float>
    val holdSpeedTwoFinger: Flow<Float>
    suspend fun setHoldSpeedOneFinger(speed: Float)
    suspend fun setHoldSpeedTwoFinger(speed: Float)
}
