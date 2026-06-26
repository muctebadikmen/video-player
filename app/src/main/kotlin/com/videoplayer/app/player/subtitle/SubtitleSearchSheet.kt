// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoplayer.core.playback.SubtitleSearchResult
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchSheet(
    state: SearchUiState,
    onDownload: (SubtitleSearchResult) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Online subtitles", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is SearchUiState.NotLoggedIn -> {
                    Text("Sign in to OpenSubtitles to search online subtitles.")
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
                is SearchUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp)); Text("Searching…")
                }
                is SearchUiState.Offline -> Text("No internet connection. Check your network and try again.")
                is SearchUiState.QuotaExhausted -> Text("Daily download quota reached. Try again after it resets.")
                is SearchUiState.Empty -> Text("No subtitles found for this video.")
                is SearchUiState.Error -> Text(state.message)
                is SearchUiState.Results -> {
                    Text("${state.remaining} downloads left today", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(state.items, key = { it.fileId }) { r ->
                            ResultRow(r, onDownload)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SubtitleSearchResult, onDownload: (SubtitleSearchResult) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val badges = buildList {
                if (r.hashMatch) add("exact match")
                if (r.fromTrusted) add("trusted")
                if (r.machineTranslated) add("auto-translated")
            }.joinToString(" · ")
            Text("${r.language.uppercase()} · ${r.release.ifBlank { r.fileName }}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "↓${r.downloadCount}  ★${"%.1f".format(Locale.ROOT, r.rating)}" + (if (badges.isNotEmpty()) "  ·  $badges" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onDownload(r) }) { Text("Download") }
    }
}
