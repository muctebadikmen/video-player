package com.videoplayer.app.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.PlaybackMemoryRepository
import com.videoplayer.app.data.memory.ResolvedStartSettings
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns playback persistence for the player screen: loads the resolved start settings
 * when a file opens (silent auto-resume) and writes current state back as it changes.
 */
class PlayerViewModel(private val repo: PlaybackMemoryRepository) : ViewModel() {

    private val _resolved = MutableStateFlow<ResolvedStartSettings?>(null)
    val resolved: StateFlow<ResolvedStartSettings?> = _resolved.asStateFlow()

    // Monotonic load token: a file open can transiently load a different uri (before the
    // MediaController syncs the playlist index), so two loads may be in flight. Only the
    // latest load's result may set _resolved — otherwise an earlier file's settings can
    // clobber the current file's (a stale-resolved race that broke per-file subtitle restore).
    private var loadSeq = 0

    fun load(mediaUri: String) {
        _resolved.value = null
        val seq = ++loadSeq
        viewModelScope.launch {
            val r = repo.resolveStart(mediaUri)
            if (seq == loadSeq) _resolved.value = r
        }
    }

    fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
    ) {
        viewModelScope.launch {
            repo.persist(
                mediaUri, positionMs, durationMs, speed, aspectMode,
                System.currentTimeMillis(),
            )
        }
    }

    fun persistSubtitle(mediaUri: String, subtitleTrackId: String?, subtitleOffsetMs: Long?) {
        viewModelScope.launch { repo.persistSubtitle(mediaUri, subtitleTrackId, subtitleOffsetMs, System.currentTimeMillis()) }
    }

    fun persistOrientation(mediaUri: String, orientation: Int?) {
        viewModelScope.launch { repo.persistOrientation(mediaUri, orientation, System.currentTimeMillis()) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val settings = SettingsRepository(appContext.settingsDataStore)
                PlayerViewModel(PlaybackMemoryRepository(db.playbackMemoryDao(), settings))
            }
        }
    }
}
