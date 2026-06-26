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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.videoplayer.core.playback.PlaybackState
import com.videoplayer.core.playback.SPEED_PRESETS
import com.videoplayer.core.playback.TextTrackInfo

/** Phase of the guided two-point precise-sync capture, driven from PlayerScreen. */
enum class TwoPointPhase { IDLE, WAITING_FIRST, WAITING_SECOND }

/**
 * Custom playback control overlay rendered on top of the video surface. Renders
 * purely from [state]; all actions are delegated up. Visibility/auto-hide is
 * owned by the caller ([PlayerScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(
    state: PlaybackState,
    aspectLabel: String,
    onCycleAspect: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasNext: Boolean,
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
    onSetThumbnail: () -> Unit,
    orientationLabel: String,
    onCycleOrientation: () -> Unit,
    pipSupported: Boolean,
    onEnterPip: () -> Unit,
    subtitleOptions: List<SubtitleOption>,
    selectedSubtitleUri: String?,
    onSelectSubtitle: (String?) -> Unit,
    onLoadSubtitleFile: () -> Unit,
    onSearchOnline: () -> Unit,
    onOpenSyncSheet: () -> Unit,
    textTracks: List<TextTrackInfo>,
    selectedTextTrackId: String?,
    onSelectEmbedded: (String) -> Unit,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
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
            IconButton(onClick = { onOpenOptions() }) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Player options",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onLock) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Lock screen",
                    tint = Color.White,
                )
            }
        }

        // Center transport: previous · play/pause · next.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous video",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(72.dp),
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
            IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next video",
                    // Grey out at the end of the queue (no wrap-around).
                    tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Secondary control row: speed picker + CC. Frame-step, A-B, sleep and
            // orientation now live in the Player options sheet.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        DropdownMenuItem(
                            text = { Text("Search online (OpenSubtitles)") },
                            onClick = {
                                onSearchOnline()
                                subtitleMenuExpanded = false
                            },
                        )
                        // Sync controls — only meaningful while an external/downloaded subtitle is
                        // active (embedded engine-rendered tracks are not re-timeable). Opens a
                        // dedicated, roomy sheet instead of cramming editors into this menu.
                        if (selectedSubtitleUri != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Adjust sync…") },
                                onClick = {
                                    onOpenSyncSheet()
                                    subtitleMenuExpanded = false
                                },
                            )
                        }
                    }
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