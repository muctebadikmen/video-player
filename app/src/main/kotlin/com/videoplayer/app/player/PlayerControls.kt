package com.videoplayer.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.videoplayer.core.model.formatDuration
import com.videoplayer.core.playback.PlaybackState

/**
 * Custom playback control overlay rendered on top of the video surface. Renders
 * purely from [state]; all actions are delegated up. Visibility/auto-hide is
 * owned by the caller ([PlayerScreen]).
 */
@Composable
fun PlayerControls(
    state: PlaybackState,
    aspectLabel: String,
    onCycleAspect: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        TextButton(
            onClick = onCycleAspect,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Text(aspectLabel, color = Color.White)
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.align(Alignment.Center).size(72.dp),
        ) {
            if (state.isPlaying) {
                PauseGlyph()
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatDuration(state.positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            val duration = state.durationMs.coerceAtLeast(0)
            Slider(
                value = state.positionMs.coerceIn(0, duration).toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(duration),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Two bars forming a pause symbol (material-icons-core has Play but not Pause). */
@Composable
private fun PauseGlyph() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = "Pause" },
    ) {
        repeat(2) {
            Box(
                Modifier
                    .size(width = 10.dp, height = 44.dp)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
    }
}
