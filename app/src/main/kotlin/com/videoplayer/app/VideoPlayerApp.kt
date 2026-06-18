package com.videoplayer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.videoplayer.app.library.LibraryScreen
import com.videoplayer.app.library.LibraryViewModel
import com.videoplayer.app.player.PlayerScreen
import com.videoplayer.core.model.MediaItem

/**
 * Compose root. Single-level navigation between the library and the player —
 * no nav library yet (YAGNI); selecting an item shows the player, back returns.
 */
@Composable
fun VideoPlayerApp() {
    val appContext = LocalContext.current.applicationContext
    val libraryViewModel: LibraryViewModel = viewModel {
        val db = AppDatabase.getInstance(appContext)
        LibraryViewModel(
            MediaStoreRepository(appContext),
            PlaybackMemoryRepository(db.playbackMemoryDao(), SettingsRepository(appContext.settingsDataStore)),
        )
    }

    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    val current = selected
    val playlist = remember(current, uiState.folders, uiState.videos) {
        val c = current ?: return@remember emptyList()
        uiState.folders.firstOrNull { f -> f.items.any { it.uri == c.uri } }?.items
            ?: uiState.videos.takeIf { vs -> vs.any { it.uri == c.uri } }
            ?: listOf(c)
    }

    if (current != null) {
        PlayerScreen(
            playlist = playlist,
            startUri = current.uri,
            onBack = { selected = null },
        )
    } else {
        Scaffold { innerPadding ->
            LibraryScreen(
                viewModel = libraryViewModel,
                onItemClick = { selected = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}
