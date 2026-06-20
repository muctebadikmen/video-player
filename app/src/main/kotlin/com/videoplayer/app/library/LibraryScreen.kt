// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onOpenDrawer: () -> Unit = {},
    scopeName: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readVideoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        if (hasPermission) viewModel.refresh() else permissionLauncher.launch(readVideoPermission)
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
            onCycleGridSize = viewModel::cycleGridSize,
            onOpenSettings = onOpenSettings,
            onOpenDrawer = onOpenDrawer,
            scopeName = scopeName,
        )
        val hasAnyItems = state.folders.isNotEmpty() || state.videos.isNotEmpty() || state.continueWatching.isNotEmpty()
        when (libraryBodyState(hasPermission, state.isLoading, hasAnyItems)) {
            LibraryBodyState.PERMISSION -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Allow access to your videos", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { permissionLauncher.launch(readVideoPermission) }) { Text("Grant access") }
                }
            }
            LibraryBodyState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            LibraryBodyState.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text("No videos found", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Add a folder from the menu to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LibraryBodyState.CONTENT -> {
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
                        folders = state.folders, gridSize = state.gridSize,
                        query = state.query,
                        progress = state.progressByUri, onItemClick = onItemClick,
                    )
                    LibraryTab.VIDEOS -> VideosContent(
                        videos = state.videos, viewMode = state.viewMode, gridSize = state.gridSize,
                        progress = state.progressByUri, onItemClick = onItemClick,
                    )
                }
            }
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
    onCycleGridSize: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    scopeName: String? = null,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    if (scopeName != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = scopeName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
        }
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
                Icon(SortIcon, contentDescription = "Sort")
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
                Icon(GridViewIcon, contentDescription = "Switch to grid view")
            } else {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Switch to list view")
            }
        }
        // Grid size cycle (only visible in grid mode)
        if (viewMode == ViewMode.GRID) {
            IconButton(onClick = onCycleGridSize) {
                Icon(Icons.Default.Apps, contentDescription = "Cycle grid size")
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
                    onClick = { onItemClick(ui.item) },
                    modifier = Modifier.width(160.dp),
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
    gridSize: GridSize,
    progress: Map<String, Float>,
    onItemClick: (MediaItem) -> Unit,
) {
    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridSize.columns),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(videos, key = { it.id }) { item ->
                ThumbnailTile(
                    item = item,
                    progress = progress[item.uri] ?: 0f,
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

// ─── Shared item composables ──────────────────────────────────────────────────

@Composable
internal fun ThumbnailTile(
    item: MediaItem,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            VideoThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())

            // Bottom gradient scrim — keeps duration chip legible over bright frames
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                        ),
                    )
                    .padding(bottom = if (progress > 0f) 4.dp else 0.dp)
                    .padding(top = 16.dp),
            )

            // Duration chip — bottom-end corner, hidden when duration unknown
            if (item.durationMs > 0L) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = if (progress > 0f) 6.dp else 4.dp),
                ) {
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            // Resume progress bar — pinned at very bottom edge, over the scrim
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
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MediaRow(
    mediaItem: MediaItem,
    progress: Float,
    onClick: () -> Unit,
) {
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
            VideoThumbnail(uri = mediaItem.uri, modifier = Modifier.fillMaxSize())
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

@Composable
private fun VideoThumbnail(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.OndemandVideo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp),
        )
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .videoFrameMillis(1_000)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
