// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.SavedFolder

/**
 * Left-side navigation drawer: "All videos" global source, saved SAF folders, and "Add folder…".
 * Wraps [content] inside a [ModalNavigationDrawer].
 */
@Composable
fun LibraryDrawer(
    drawerState: DrawerState,
    savedFolders: List<SavedFolder>,
    activeSource: LibrarySourceId,
    onSelectGlobal: () -> Unit,
    onSelectFolder: (SavedFolder) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (SavedFolder) -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                    item {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("All videos") },
                            selected = activeSource is LibrarySourceId.Global,
                            onClick = onSelectGlobal,
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    items(savedFolders, key = { it.treeUri }) { folder ->
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            label = { Text(folder.displayName) },
                            selected = activeSource == LibrarySourceId.Folder(folder.treeUri),
                            onClick = { onSelectFolder(folder) },
                            badge = {
                                IconButton(onClick = { onRemoveFolder(folder) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.displayName}")
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            label = { Text("Add folder…") },
                            selected = false,
                            onClick = onAddFolder,
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
        content = content,
    )
}
