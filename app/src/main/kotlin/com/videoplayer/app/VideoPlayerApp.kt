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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoplayer.app.data.MediaStoreRepository
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
        LibraryViewModel(MediaStoreRepository(appContext))
    }

    var selected by remember { mutableStateOf<MediaItem?>(null) }
    val current = selected

    if (current != null) {
        PlayerScreen(item = current, onBack = { selected = null })
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
