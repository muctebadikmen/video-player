// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat

/** Maps a framework-agnostic [SubtitleStyleSpec] to Media3's SubtitleView caption style. */
fun captionStyleCompatFor(spec: SubtitleStyleSpec): CaptionStyleCompat {
    val edgeType = when (spec.edge) {
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
    return CaptionStyleCompat(
        spec.textColor.toInt(),
        spec.backgroundColor.toInt(),
        Color.TRANSPARENT,
        edgeType,
        spec.edgeColor.toInt(),
        null,
    )
}
