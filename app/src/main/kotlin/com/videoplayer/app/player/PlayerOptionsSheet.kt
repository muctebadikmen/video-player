// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoplayer.core.playback.AbLoop
import com.videoplayer.core.playback.FRAME_STEP_MS

/**
 * Player options ("Playback" + "Display") presented as a [ModalBottomSheet]. Hoisted out of
 * [PlayerControls] to [PlayerScreen]'s top level (like [SubtitleSyncSheet]) so the controls'
 * 3s auto-hide can no longer tear the sheet down mid-interaction. Renders purely from the
 * passed-in state; all mutations are delegated up to [PlayerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerOptionsSheet(
    abLoop: AbLoop,
    sleepActive: Boolean,
    orientationLabel: String,
    onFrameStep: (Long) -> Unit,
    onToggleAb: () -> Unit,
    onPickSleep: (SleepOption) -> Unit,
    onCycleOrientation: () -> Unit,
    onSetThumbnail: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OptionsSectionHeader("Playback")

            // Frame step
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Frame step", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onFrameStep(-FRAME_STEP_MS) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous frame",
                        )
                    }
                    IconButton(onClick = { onFrameStep(FRAME_STEP_MS) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next frame",
                        )
                    }
                }
            }

            // A–B repeat
            val abLabel = when {
                abLoop.isComplete -> "A–B ✓"
                abLoop.startMs != null -> "A•"
                else -> "A–B"
            }
            OptionsActionRow(
                title = "A–B repeat",
                trailing = abLabel,
                highlighted = abLoop.startMs != null,
                onClick = onToggleAb,
            )

            // Sleep timer
            Text(
                "Sleep timer",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            SleepOption.entries.forEach { option ->
                OptionsActionRow(
                    title = option.label,
                    highlighted = sleepActive && option != SleepOption.OFF,
                    onClick = { onPickSleep(option) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OptionsSectionHeader("Display")

            OptionsActionRow(
                title = "Orientation",
                trailing = orientationLabel,
                onClick = onCycleOrientation,
            )
            OptionsActionRow(
                title = "Set as thumbnail",
                onClick = onSetThumbnail,
            )
        }
    }
}

@Composable
private fun OptionsSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun OptionsActionRow(
    title: String,
    trailing: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
