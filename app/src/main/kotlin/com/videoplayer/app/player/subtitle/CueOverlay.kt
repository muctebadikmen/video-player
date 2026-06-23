// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Renders the active cue for the *external/sibling* subtitle path with the user's chosen
 * style/size/position. Embedded tracks are styled separately on Media3's SubtitleView, but
 * both paths read the same fractions so they look identical.
 */
@Composable
fun CueOverlay(
    text: String?,
    style: SubtitleStyle,
    sizeFraction: Float,
    bottomPaddingFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
    val spec = subtitleStyleSpec(style)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val fontSp = with(density) { (sizeFraction * heightPx).toSp() }
        val bottomDp = with(density) { (bottomPaddingFraction * heightPx).toDp() }
        val strokeWidthPx = (sizeFraction * heightPx) * 0.10f
        val textColor = Color(spec.textColor.toInt())
        val edgeColor = Color(spec.edgeColor.toInt())

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = bottomDp),
            contentAlignment = Alignment.Center,
        ) {
            when (spec.edge) {
                SubtitleEdge.OUTLINE -> {
                    // Stroke layer behind a solid fill layer = crisp outline.
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        style = TextStyle(color = edgeColor, drawStyle = Stroke(width = strokeWidthPx)),
                    )
                    Text(text = text, textAlign = TextAlign.Center, fontSize = fontSp, color = textColor)
                }
                SubtitleEdge.DROP_SHADOW -> {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        color = textColor,
                        style = TextStyle(
                            shadow = Shadow(
                                color = edgeColor,
                                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                blurRadius = strokeWidthPx * 1.5f,
                            ),
                        ),
                    )
                }
                SubtitleEdge.NONE -> {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        color = textColor,
                        modifier = Modifier
                            .background(Color(spec.backgroundColor.toInt()), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
