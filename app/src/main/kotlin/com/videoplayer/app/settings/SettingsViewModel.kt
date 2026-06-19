package com.videoplayer.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val backgroundPlaybackEnabled: StateFlow<Boolean> =
        repo.backgroundPlaybackEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), true,
        )

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { repo.setBackgroundPlaybackEnabled(enabled) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(SettingsRepository(context.applicationContext.settingsDataStore))
            }
        }
    }
}
