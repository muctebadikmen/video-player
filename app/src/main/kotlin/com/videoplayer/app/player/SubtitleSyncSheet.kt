// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.videoplayer.core.playback.SUBTITLE_NUDGE_MS

/**
 * Dedicated, roomy subtitle-sync editor presented as a [ModalBottomSheet]. Replaces the
 * cramped CC-dropdown sync items. Renders purely from the passed-in state; all mutations are
 * delegated up to [PlayerScreen], so the existing per-file persistence and clamps are preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSyncSheet(
    offsetMs: Long,
    onNudge: (Long) -> Unit, // pass SUBTITLE_NUDGE_MS multiples; PlayerScreen clamps
    onResetOffset: () -> Unit,
    rate: Float,
    onAdjustRate: (Float) -> Unit,
    onResetRate: () -> Unit,
    twoPointPhase: TwoPointPhase,
    onStartTwoPoint: () -> Unit,
    onMarkTwoPoint: () -> Unit,
    onCancelTwoPoint: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Subtitle sync", style = MaterialTheme.typography.titleMedium)

            // ── Delay (offset) ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "%+d ms".format(offsetMs),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { onNudge(-10 * SUBTITLE_NUDGE_MS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−500ms") }
                    FilledTonalButton(
                        onClick = { onNudge(-SUBTITLE_NUDGE_MS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−50ms") }
                    OutlinedButton(
                        onClick = onResetOffset,
                        modifier = Modifier.weight(1f),
                    ) { Text("Reset") }
                    FilledTonalButton(
                        onClick = { onNudge(SUBTITLE_NUDGE_MS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+50ms") }
                    FilledTonalButton(
                        onClick = { onNudge(10 * SUBTITLE_NUDGE_MS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+500ms") }
                }
                Text(
                    text = "− later · + earlier",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            // ── Speed / rate ──────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "%.3f×".format(rate),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { onAdjustRate(-0.01f) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−0.01") }
                    FilledTonalButton(
                        onClick = { onAdjustRate(-0.001f) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−0.001") }
                    OutlinedButton(
                        onClick = onResetRate,
                        modifier = Modifier.weight(1f),
                    ) { Text("Reset") }
                    FilledTonalButton(
                        onClick = { onAdjustRate(0.001f) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+0.001") }
                    FilledTonalButton(
                        onClick = { onAdjustRate(0.01f) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+0.01") }
                }
            }

            // ── Precise sync (two-point) ─────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Precise sync", style = MaterialTheme.typography.titleSmall)
                when (twoPointPhase) {
                    TwoPointPhase.IDLE -> {
                        Text(
                            "Match two lines to their real timings and let the player " +
                                "compute the delay and speed for you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = onStartTwoPoint,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Start precise sync (2-point)") }
                    }
                    TwoPointPhase.WAITING_FIRST -> {
                        ProgressDots(step = 1)
                        Text(
                            "Step 1 of 2 — play to the first subtitle line, then tap Mark line 1.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onMarkTwoPoint,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark line 1") }
                        TextButton(
                            onClick = onCancelTwoPoint,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                    TwoPointPhase.WAITING_SECOND -> {
                        ProgressDots(step = 2)
                        Text(
                            "Step 2 of 2 — play to a later line, then tap Mark line 2.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onMarkTwoPoint,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark line 2") }
                        TextButton(
                            onClick = onCancelTwoPoint,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Two-step progress indicator: filled dots up to [step] (1-based), hollow afterwards. */
@Composable
private fun ProgressDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 1..2) {
            val filled = i <= step
            Surface(
                shape = CircleShape,
                color = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(12.dp),
            ) {}
        }
    }
}
