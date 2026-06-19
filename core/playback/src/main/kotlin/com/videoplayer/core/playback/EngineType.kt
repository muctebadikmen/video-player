// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/**
 * Which underlying decoder/engine is driving playback. The UI never branches on
 * this beyond showing a label — Media3 is the default; MPV (libmpv) joins in a
 * later phase as the power/fallback engine behind the same [PlaybackEngine].
 */
enum class EngineType { MEDIA3, MPV }