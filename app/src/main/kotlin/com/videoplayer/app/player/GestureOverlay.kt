// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Transient centered chip showing the live value of an in-progress gesture
 * (brightness, volume, seek target, speed boost). Caller owns show/hide timing
 * and formats [label] (e.g. "Brightness 60%", "Volume 150%", "» 0:34", "2×").
 */
@Composable
fun GestureOverlay(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}