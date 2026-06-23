// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import com.videoplayer.app.player.subtitle.SubtitleStyle
import kotlinx.coroutines.flow.Flow

/** Global subtitle appearance prefs, shared by both render paths and the Settings UI. */
interface SubtitlePreferences {
    val subtitleStyle: Flow<SubtitleStyle>
    val subtitleSizeFraction: Flow<Float>
    val subtitleBottomPaddingFraction: Flow<Float>
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setSubtitleSizeFraction(fraction: Float)
    suspend fun setSubtitleBottomPaddingFraction(fraction: Float)
}
