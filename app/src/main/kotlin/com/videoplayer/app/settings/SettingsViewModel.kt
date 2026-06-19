// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesClient
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesCredentials
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesCredentialsRepository
import com.videoplayer.app.data.opensubtitles.OsError
import com.videoplayer.app.data.opensubtitles.OsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val osRepo: OpenSubtitlesCredentialsRepository,
    private val versionName: String,
) : ViewModel() {
    val backgroundPlaybackEnabled: StateFlow<Boolean> =
        repo.backgroundPlaybackEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), true,
        )

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { repo.setBackgroundPlaybackEnabled(enabled) }
    }

    val osCredentials: StateFlow<OpenSubtitlesCredentials> =
        osRepo.credentials.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            OpenSubtitlesCredentials("", "", null, "https://api.opensubtitles.com/api/v1", 0, 0, null, listOf("tr", "en")),
        )

    val osLoginStatus = MutableStateFlow<String?>(null) // null = idle; else a status/error message

    fun setApiKey(key: String) { viewModelScope.launch { osRepo.setApiKey(key) } }
    fun setUsername(name: String) { viewModelScope.launch { osRepo.setUsername(name) } }
    fun setFavoriteLanguages(csv: String) { viewModelScope.launch { osRepo.setFavoriteLanguages(csv) } }
    fun logout() { viewModelScope.launch { osRepo.logout(); osLoginStatus.value = null } }

    fun login(apiKey: String, username: String, password: String) {
        viewModelScope.launch {
            osLoginStatus.value = "Signing in…"
            osRepo.setApiKey(apiKey); osRepo.setUsername(username)
            val client = OpenSubtitlesClient(versionName = versionName)
            when (val r = client.login(apiKey, username, password)) {
                is OsResult.Success -> {
                    osRepo.saveSession(r.value.token, r.value.baseUrl, r.value.allowedDownloads, r.value.level)
                    osLoginStatus.value = "Signed in"
                }
                is OsResult.Failure -> osLoginStatus.value = when (val e = r.error) {
                    is OsError.Offline -> "No internet connection"
                    is OsError.Http -> if (e.code == 401) "Wrong username/password or Api-Key" else "Login failed (${e.code})"
                    else -> "Login failed"
                }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                val versionName = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.3.0"
                SettingsViewModel(
                    SettingsRepository(app.settingsDataStore),
                    OpenSubtitlesCredentialsRepository(app.settingsDataStore),
                    versionName,
                )
            }
        }
    }
}
