// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.videoplayer.app.player.subtitle.SubtitleOption
import com.videoplayer.core.model.formatDuration
import com.videoplayer.core.playback.AbLoop
import com.videoplayer.core.playback.FRAME_STEP_MS
import com.videoplayer.core.playback.PlaybackState
import com.videoplayer.core.playback.SPEED_PRESETS
import com.videoplayer.core.playback.SUBTITLE_NUDGE_MS
import com.videoplayer.core.playback.TextTrackInfo

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
    currentSpeed: Float,
    onSetSpeed: (Float) -> Unit,
    onFrameStep: (Long) -> Unit,
    abLoop: AbLoop,
    onToggleAb: () -> Unit,
    sleepActive: Boolean,
    onPickSleep: (SleepOption) -> Unit,
    onLock: () -> Unit,
    orientationLabel: String,
    onCycleOrientation: () -> Unit,
    pipSupported: Boolean,
    onEnterPip: () -> Unit,
    subtitleOptions: List<SubtitleOption>,
    selectedSubtitleUri: String?,
    subtitleOffsetMs: Long,
    onSelectSubtitle: (String?) -> Unit,
    onLoadSubtitleFile: () -> Unit,
    onNudgeSubtitle: (Long) -> Unit,
    textTracks: List<TextTrackInfo>,
    selectedTextTrackId: String?,
    onSelectEmbedded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepMenuExpanded by remember { mutableStateOf(false) }
    var subtitleMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCycleAspect) {
                Text(aspectLabel, color = Color.White)
            }
            if (pipSupported) {
                TextButton(onClick = onEnterPip) {
                    Text("PiP", color = Color.White)
                }
            }
            IconButton(onClick = onLock) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Lock screen",
                    tint = Color.White,
                )
            }
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Secondary control row: speed, frame-step, A-B, sleep
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Speed picker
                Box {
                    val speedLabel = if (currentSpeed == currentSpeed.toLong().toFloat()) {
                        "${currentSpeed.toLong()}×"
                    } else {
                        "${currentSpeed}×"
                    }
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text(speedLabel, color = Color.White)
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false },
                    ) {
                        SPEED_PRESETS.forEach { preset ->
                            val label = if (preset == preset.toLong().toFloat()) {
                                "${preset.toLong()}×"
                            } else {
                                "${preset}×"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onSetSpeed(preset)
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                // Frame-step previous
                IconButton(onClick = { onFrameStep(-FRAME_STEP_MS) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous frame",
                        tint = Color.White,
                    )
                }

                // Frame-step next
                IconButton(onClick = { onFrameStep(FRAME_STEP_MS) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next frame",
                        tint = Color.White,
                    )
                }

                // A-B repeat button
                val abLabel = when {
                    abLoop.isComplete -> "A–B ✓"
                    abLoop.startMs != null -> "A•"
                    else -> "A–B"
                }
                TextButton(onClick = onToggleAb) {
                    Text(abLabel, color = Color.White)
                }

                // CC subtitle picker
                Box {
                    TextButton(onClick = { subtitleMenuExpanded = true }) {
                        Text(
                            text = "CC",
                            color = if (selectedSubtitleUri != null || selectedTextTrackId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = subtitleMenuExpanded,
                        onDismissRequest = { subtitleMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Off") },
                            onClick = {
                                onSelectSubtitle(null)
                                subtitleMenuExpanded = false
                            },
                            trailingIcon = {
                                if (selectedSubtitleUri == null && selectedTextTrackId == null) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                        )
                        textTracks.forEach { track ->
                            DropdownMenuItem(
                                text = { Text(track.label) },
                                onClick = {
                                    onSelectEmbedded(track.id)
                                    subtitleMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (selectedTextTrackId == track.id) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                            )
                        }
                        subtitleOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onSelectSubtitle(option.uri)
                                    subtitleMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (selectedSubtitleUri == option.uri) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Load subtitle file…") },
                            onClick = {
                                onLoadSubtitleFile()
                                subtitleMenuExpanded = false
                            },
                        )
                        // Sync nudge — only meaningful while a custom subtitle is active. Items keep the
                        // menu open so the user can tap repeatedly; the offset readout updates live.
                        if (selectedSubtitleUri != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Sync −50ms (delay)") },
                                onClick = { onNudgeSubtitle(-SUBTITLE_NUDGE_MS) },
                            )
                            DropdownMenuItem(
                                text = { Text("Offset: ${subtitleOffsetMs} ms") },
                                onClick = {},
                                enabled = false,
                            )
                            DropdownMenuItem(
                                text = { Text("Sync +50ms (earlier)") },
                                onClick = { onNudgeSubtitle(SUBTITLE_NUDGE_MS) },
                            )
                        }
                    }
                }

                // Sleep timer
                // Icons.Filled.Bedtime and Icons.Filled.Schedule are not in material-icons-core;
                // using a TextButton with emoji label instead.
                Box {
                    TextButton(
                        onClick = { sleepMenuExpanded = true },
                    ) {
                        Text(
                            text = "💤",
                            color = if (sleepActive) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = sleepMenuExpanded,
                        onDismissRequest = { sleepMenuExpanded = false },
                    ) {
                        SleepOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onPickSleep(option)
                                    sleepMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                // Orientation cycle
                TextButton(onClick = onCycleOrientation) {
                    Text(orientationLabel, color = Color.White)
                }
            }

            // Seek bar row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
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