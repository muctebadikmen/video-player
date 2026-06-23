// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration

private const val CANDIDATE_COUNT = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailPickerSheet(
    item: MediaItem,
    hasCustom: Boolean,
    onConfirm: (frameMs: Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val duration = item.durationMs.coerceAtLeast(1L)
    // Candidate timestamps evenly spaced across the middle 80% of the video.
    val candidates = remember(duration) {
        (1..CANDIDATE_COUNT).map { i -> duration * i / (CANDIDATE_COUNT + 1) }
    }
    var selectedMs by remember { mutableLongStateOf(candidates[CANDIDATE_COUNT / 2]) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose thumbnail", style = MaterialTheme.typography.titleMedium)

            // Large live preview of the currently selected frame.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.uri).videoFrameMillis(selectedMs).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
            )
            Text(formatDuration(selectedMs), style = MaterialTheme.typography.bodySmall)

            // Fine scrub across the whole video.
            Slider(
                value = selectedMs.toFloat(),
                onValueChange = { selectedMs = it.toLong() },
                valueRange = 0f..duration.toFloat(),
            )

            // Sampled candidate strip.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates) { ms ->
                    val selected = ms == selectedMs
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.uri).videoFrameMillis(ms).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier
                            .width(120.dp).aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { selectedMs = ms },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasCustom) {
                    TextButton(onClick = { onReset(); onDismiss() }) { Text("Reset to automatic") }
                }
                Box(Modifier.weight(1f))
                Button(onClick = { onConfirm(selectedMs); onDismiss() }) { Text("Set thumbnail") }
            }
        }
    }
}
