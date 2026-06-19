package com.videoplayer.app.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoplayer.app.data.memory.rememberThumbnail
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.SortKey
import com.videoplayer.core.model.SortOrder
import com.videoplayer.core.model.formatDuration

private val readVideoPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Library browser: tabbed Folders/Videos view with list⇄grid toggle, thumbnails,
 * resume-progress indicators, a "Continue watching" row, search, and sort.
 * Requests the read-media permission on first display and refreshes once granted.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onItemClick: (MediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

    if (state.folders.isEmpty() && state.videos.isEmpty() && state.continueWatching.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No videos found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryTopBar(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            sortKey = state.sortKey,
            sortOrder = state.sortOrder,
            onSortChange = viewModel::setSort,
            viewMode = state.viewMode,
            onToggleViewMode = {
                viewModel.setViewMode(if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
            },
            onOpenSettings = onOpenSettings,
        )

        if (state.continueWatching.isNotEmpty()) {
            ContinueWatchingRow(items = state.continueWatching, onItemClick = onItemClick)
        }

        TabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == LibraryTab.FOLDERS,
                onClick = { viewModel.setTab(LibraryTab.FOLDERS) },
                text = { Text("Folders") },
            )
            Tab(
                selected = state.tab == LibraryTab.VIDEOS,
                onClick = { viewModel.setTab(LibraryTab.VIDEOS) },
                text = { Text("Videos") },
            )
        }

        when (state.tab) {
            LibraryTab.FOLDERS -> FoldersContent(
                folders = state.folders,
                viewMode = state.viewMode,
                progress = state.progressByUri,
                onItemClick = onItemClick,
            )
            LibraryTab.VIDEOS -> VideosContent(
                videos = state.videos,
                viewMode = state.viewMode,
                progress = state.progressByUri,
                onItemClick = onItemClick,
            )
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortKey: SortKey,
    sortOrder: SortOrder,
    onSortChange: (SortKey, SortOrder) -> Unit,
    viewMode: ViewMode,
    onToggleViewMode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search videos…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            ),
        )

        // Sort control
        Box {
            IconButton(onClick = { sortMenuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Sort")
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                SortKey.entries.forEach { key ->
                    val isSelected = key == sortKey
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(key.displayName())
                                if (isSelected) {
                                    Text(
                                        if (sortOrder == SortOrder.ASC) "↑" else "↓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            val newOrder = if (isSelected && sortOrder == SortOrder.ASC) {
                                SortOrder.DESC
                            } else {
                                SortOrder.ASC
                            }
                            onSortChange(key, newOrder)
                            sortMenuExpanded = false
                        },
                    )
                }
            }
        }

        // List / Grid toggle
        IconButton(onClick = onToggleViewMode) {
            if (viewMode == ViewMode.LIST) {
                Icon(Icons.Default.Menu, contentDescription = "Switch to grid view")
            } else {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Switch to list view")
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

private fun SortKey.displayName(): String = when (this) {
    SortKey.NAME -> "Name"
    SortKey.DATE_ADDED -> "Date added"
    SortKey.DURATION -> "Duration"
}

// ─── Continue watching ────────────────────────────────────────────────────────

@Composable
private fun ContinueWatchingRow(items: List<LibraryItemUi>, onItemClick: (MediaItem) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Continue watching",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.item.id }) { ui ->
                ThumbnailTile(
                    item = ui.item,
                    progress = ui.progress,
                    width = 160.dp,
                    onClick = { onItemClick(ui.item) },
                )
            }
        }
        HorizontalDivider()
    }
}

// ─── Videos tab content ───────────────────────────────────────────────────────

@Composable
private fun VideosContent(
    videos: List<MediaItem>,
    viewMode: ViewMode,
    progress: Map<String, Float>,
    onItemClick: (MediaItem) -> Unit,
) {
    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(videos, key = { it.id }) { item ->
                ThumbnailTile(
                    item = item,
                    progress = progress[item.uri] ?: 0f,
                    width = 140.dp,
                    onClick = { onItemClick(item) },
                )
            }
        }
    } else {
        LazyColumn {
            items(videos, key = { it.id }) { item ->
                MediaRow(
                    mediaItem = item,
                    progress = progress[item.uri] ?: 0f,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

// ─── Folders tab content ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoldersContent(
    folders: List<MediaFolder>,
    viewMode: ViewMode,
    progress: Map<String, Float>,
    onItemClick: (MediaItem) -> Unit,
) {
    LazyColumn {
        folders.forEach { folder ->
            item(key = "folder:${folder.path}") {
                Text(
                    text = "${folder.name} · ${folder.videoCount}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (viewMode == ViewMode.GRID) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        folder.items.forEach { item ->
                            ThumbnailTile(
                                item = item,
                                progress = progress[item.uri] ?: 0f,
                                width = 140.dp,
                                onClick = { onItemClick(item) },
                            )
                        }
                    }
                }
            }
            if (viewMode == ViewMode.LIST) {
                items(folder.items, key = { "item:${it.id}" }) { item ->
                    MediaRow(
                        mediaItem = item,
                        progress = progress[item.uri] ?: 0f,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

// ─── Shared item composables ──────────────────────────────────────────────────

@Composable
private fun ThumbnailTile(
    item: MediaItem,
    progress: Float,
    width: Dp,
    onClick: () -> Unit,
) {
    val bitmap = rememberThumbnail(item.uri)
    Column(
        Modifier
            .width(width)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = formatDuration(item.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun MediaRow(
    mediaItem: MediaItem,
    progress: Float,
    onClick: () -> Unit,
) {
    val bitmap = rememberThumbnail(mediaItem.uri)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading thumbnail
        Box(
            Modifier
                .width(80.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // Text + optional progress
        Column(Modifier.weight(1f)) {
            Text(
                text = mediaItem.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDuration(mediaItem.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}
