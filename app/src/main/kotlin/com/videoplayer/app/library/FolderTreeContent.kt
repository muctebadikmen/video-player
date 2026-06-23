// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.videoplayer.app.thumbnail.ThumbnailSpec
import com.videoplayer.core.model.FolderNode
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.buildFolderTree

// ─── Row model ───────────────────────────────────────────────────────────────

private sealed interface TreeRow {
    val depth: Int
    data class Folder(val node: FolderNode, override val depth: Int, val expanded: Boolean) : TreeRow
    data class Videos(val node: FolderNode, override val depth: Int) : TreeRow
}

private fun flattenTree(
    nodes: List<FolderNode>,
    expanded: Set<String>,
    depth: Int = 0,
): List<TreeRow> = buildList {
    for (node in nodes) {
        val isExpanded = node.path in expanded
        add(TreeRow.Folder(node, depth, isExpanded))
        if (isExpanded) {
            if (node.items.isNotEmpty()) add(TreeRow.Videos(node, depth + 1))
            addAll(flattenTree(node.children, expanded, depth + 1))
        }
    }
}

// ─── FoldersContent (tree) ────────────────────────────────────────────────────

@Composable
internal fun FoldersContent(
    folders: List<MediaFolder>,
    gridSize: GridSize,
    query: String,
    progress: Map<String, Float>,
    thumbnailByUri: Map<String, ThumbnailSpec>,
    onEnsure: (MediaItem) -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    val tree = remember(folders) { buildFolderTree(folders) }

    // Fix 3: SnapshotStateList as single source of truth — toggle lambda reads the
    // live list at click time, eliminating the stale-closure problem.
    // Fix 2: listSaver stores individual paths (safe for FS paths with spaces;
    // no delimiter ambiguity since each path is its own list element).
    val expanded = rememberSaveable(
        saver = listSaver(
            save    = { list -> list.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    // Fix 1: hoist remember unconditionally — calling remember inside a conditional
    // violates Compose rules and causes runtime crashes.
    val allPaths = remember(tree) { collectAllPaths(tree) }

    // When a query is active, auto-expand every node so matches are visible.
    val effectivePaths: Set<String> = if (query.isNotBlank()) allPaths else expanded.toSet()

    val rows = remember(tree, effectivePaths) { flattenTree(tree, effectivePaths) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { row ->
            when (row) {
                is TreeRow.Folder -> "f:${row.node.path}"
                is TreeRow.Videos -> "v:${row.node.path}"
            }
        }) { row ->
            when (row) {
                is TreeRow.Folder -> FolderRow(
                    node = row.node,
                    depth = row.depth,
                    expanded = row.expanded,
                    onToggle = {
                        // Fix 3: reads live list — no stale capture.
                        if (!expanded.remove(row.node.path)) expanded.add(row.node.path)
                    },
                )
                is TreeRow.Videos -> VideoGridRow(
                    items = row.node.items,
                    depth = row.depth,
                    gridSize = gridSize,
                    progress = progress,
                    thumbnailByUri = thumbnailByUri,
                    onEnsure = onEnsure,
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

private fun collectAllPaths(nodes: List<FolderNode>): Set<String> = buildSet {
    for (node in nodes) {
        add(node.path)
        addAll(collectAllPaths(node.children))
    }
}

// ─── FolderRow ────────────────────────────────────────────────────────────────

@Composable
private fun FolderRow(
    node: FolderNode,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron",
    )
    val hasChildren = node.children.isNotEmpty() || node.items.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start = (16 + depth * 16).dp,
                end = 16.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = if (hasChildren) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0f)
            },
            modifier = Modifier.rotate(chevronRotation),
        )
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = "${node.totalVideoCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── VideoGridRow ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun VideoGridRow(
    items: List<MediaItem>,
    depth: Int,
    gridSize: GridSize,
    progress: Map<String, Float>,
    thumbnailByUri: Map<String, ThumbnailSpec>,
    onEnsure: (MediaItem) -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    val columns = gridSize.columns
    val startIndent = (depth * 16).dp
    val endPad = 12.dp
    val spacing = 12.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startIndent,
                end = endPad,
                top = 4.dp,
                bottom = 8.dp,
            ),
    ) {
        // maxWidth here is the width after the padding above has been applied,
        // so the tile budget is the full available width minus inter-tile spacing.
        val tileWidth = (maxWidth - spacing * (columns - 1)) / columns

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items.forEach { item ->
                ThumbnailTile(
                    item = item,
                    progress = progress[item.uri] ?: 0f,
                    spec = thumbnailByUri[item.uri],
                    onEnsure = onEnsure,
                    onClick = { onItemClick(item) },
                    onLongPress = {},
                    modifier = Modifier.width(tileWidth),
                )
            }
        }
    }
}
