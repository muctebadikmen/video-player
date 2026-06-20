// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoplayer.app.data.MediaStoreRepository
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.PlaybackMemoryRepository
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.LibrarySourceStore
import com.videoplayer.app.data.saf.SafFolderRepository
import com.videoplayer.app.data.saf.SavedFolder
import com.videoplayer.app.data.saf.libraryDataStore
import com.videoplayer.app.intent.synthesizeMediaItem
import com.videoplayer.app.library.LibraryDrawer
import com.videoplayer.app.library.LibraryScreen
import com.videoplayer.app.library.LibraryViewModel
import com.videoplayer.app.player.PlayerScreen
import com.videoplayer.app.settings.SettingsScreen
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.launch

/**
 * Compose root. Single-level navigation between the library and the player —
 * no nav library yet (YAGNI); selecting an item shows the player, back returns.
 */
@Composable
fun VideoPlayerApp(
    externalUri: Uri? = null,
    onExternalConsumed: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val libraryViewModel: LibraryViewModel = viewModel {
        val db = AppDatabase.getInstance(appContext)
        val settingsRepository = SettingsRepository(appContext.settingsDataStore)
        val manager = LibrarySourceManager(
            store = LibrarySourceStore(appContext.libraryDataStore),
            globalRepository = MediaStoreRepository(appContext),
            folderRepositoryFactory = { treeUri -> SafFolderRepository(appContext, Uri.parse(treeUri)) },
        )
        LibraryViewModel(
            manager,
            PlaybackMemoryRepository(db.playbackMemoryDao(), settingsRepository),
            settingsRepository,
        )
    }

    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val current = selected
    val playlist = remember(current, uiState.folders, uiState.videos) {
        val c = current ?: return@remember emptyList()
        uiState.folders.firstOrNull { f -> f.items.any { it.uri == c.uri } }?.items
            ?: uiState.videos.takeIf { vs -> vs.any { it.uri == c.uri } }
            ?: listOf(c)
    }

    // An "Open with" hands us a foreign URI; synthesize a one-item session that bypasses the library.
    var externalItem by remember(externalUri) { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(externalUri) {
        externalItem = externalUri?.let { synthesizeMediaItem(appContext, it) }
    }

    when {
        externalItem != null -> {
            val item = externalItem!!
            PlayerScreen(
                playlist = listOf(item),
                startUri = item.uri,
                onBack = {
                    externalItem = null
                    onExternalConsumed()
                },
                onOpenSettings = {
                    externalItem = null
                    onExternalConsumed()
                    showSettings = true
                },
            )
        }
        current != null -> PlayerScreen(
            playlist = playlist,
            startUri = current.uri,
            onBack = { selected = null },
            onOpenSettings = { selected = null; showSettings = true },
        )
        showSettings -> SettingsScreen(onBack = { showSettings = false })
        else -> {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            // Track which persisted URI grants are currently active. Initialized synchronously to
            // avoid an initial "everything unavailable" flicker, then refreshed when the drawer
            // opens or the saved-folder list changes.
            var persistedUris by remember {
                mutableStateOf(
                    context.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.toString() }
                        .toSet()
                )
            }
            LaunchedEffect(drawerState.isOpen, uiState.savedFolders) {
                persistedUris = context.contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission }
                    .map { it.uri.toString() }
                    .toSet()
            }
            // If the active source points at a folder whose grant has been revoked, fall back to
            // All videos so the user never lands on a silent empty scoped view.
            LaunchedEffect(persistedUris) {
                val active = uiState.activeSource
                if (active is LibrarySourceId.Folder && active.treeUri !in persistedUris) {
                    libraryViewModel.selectSource(LibrarySourceId.Global)
                }
            }
            val unavailableTreeUris = uiState.savedFolders
                .map { it.treeUri }
                .filterNot { it in persistedUris }
                .toSet()
            val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?: "Folder"
                    libraryViewModel.addFolder(SavedFolder(uri.toString(), name))
                    scope.launch { drawerState.close() }
                }
            }
            // Resolve title: show folder display name when a folder scope is active.
            val scopeName = (uiState.activeSource as? LibrarySourceId.Folder)
                ?.let { f -> uiState.savedFolders.firstOrNull { it.treeUri == f.treeUri }?.displayName }
            LibraryDrawer(
                drawerState = drawerState,
                savedFolders = uiState.savedFolders,
                activeSource = uiState.activeSource,
                onSelectGlobal = {
                    libraryViewModel.selectSource(LibrarySourceId.Global)
                    scope.launch { drawerState.close() }
                },
                onSelectFolder = { f ->
                    libraryViewModel.selectSource(LibrarySourceId.Folder(f.treeUri))
                    scope.launch { drawerState.close() }
                },
                onAddFolder = { pickFolder.launch(null) },
                onRemoveFolder = { f ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(f.treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    libraryViewModel.removeFolder(f.treeUri)
                },
                unavailableTreeUris = unavailableTreeUris,
            ) {
                Scaffold { innerPadding ->
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onItemClick = { selected = it },
                        onOpenSettings = { showSettings = true },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        scopeName = scopeName,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    )
                }
            }
        }
    }
}
