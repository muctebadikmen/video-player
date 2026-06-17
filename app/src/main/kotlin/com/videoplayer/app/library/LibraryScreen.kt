package com.videoplayer.app.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration

private val readVideoPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Library browser: lists folders that contain videos, grouped, with each video
 * tappable to play. Requests the read-media permission on first display and
 * refreshes once it is granted.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, readVideoPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) viewModel.refresh() else permissionLauncher.launch(readVideoPermission)
    }

    if (folders.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No videos found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        folders.forEach { folder ->
            item(key = "folder:${folder.path}") {
                Text(
                    text = "${folder.name} · ${folder.videoCount}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(folder.items, key = { it.id }) { mediaItem ->
                MediaRow(mediaItem = mediaItem, onClick = { onItemClick(mediaItem) })
            }
        }
    }
}

@Composable
private fun MediaRow(mediaItem: MediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(mediaItem.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            formatDuration(mediaItem.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
