// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Subtle top-center speed indicator shown while a hold-to-speed gesture is active.
 * Deliberately small and translucent so it does not interrupt the viewing experience.
 */
@Composable
fun SpeedBadge(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = "▶▶ $label",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
