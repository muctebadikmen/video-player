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
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_TWO
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_BOTTOM_PADDING
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION
import com.videoplayer.app.player.subtitle.SubtitleStyle
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
    val appVersion: String get() = versionName

    val backgroundPlaybackEnabled: StateFlow<Boolean> =
        repo.backgroundPlaybackEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), true,
        )

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { repo.setBackgroundPlaybackEnabled(enabled) }
    }

    val resumeEnabled: StateFlow<Boolean> =
        repo.resumeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val defaultSpeed: StateFlow<Float> =
        repo.defaultSpeed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)
    val gridColumns: StateFlow<Int> =
        repo.gridColumns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    fun setResumeEnabled(enabled: Boolean) { viewModelScope.launch { repo.setResumeEnabled(enabled) } }
    fun setDefaultSpeed(v: Float) { viewModelScope.launch { repo.setDefaultSpeed(v) } }
    fun setGridColumns(n: Int) { viewModelScope.launch { repo.setGridColumns(n) } }

    val holdSpeedOneFinger: StateFlow<Float> =
        repo.holdSpeedOneFinger.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_HOLD_SPEED_ONE)
    val holdSpeedTwoFinger: StateFlow<Float> =
        repo.holdSpeedTwoFinger.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_HOLD_SPEED_TWO)
    val subtitleStyle: StateFlow<SubtitleStyle> =
        repo.subtitleStyle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleStyle.OUTLINE)
    val subtitleSizeFraction: StateFlow<Float> =
        repo.subtitleSizeFraction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SUBTITLE_SIZE_FRACTION)
    val subtitleBottomPaddingFraction: StateFlow<Float> =
        repo.subtitleBottomPaddingFraction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SUBTITLE_BOTTOM_PADDING)

    fun setHoldSpeedOneFinger(v: Float) { viewModelScope.launch { repo.setHoldSpeedOneFinger(v) } }
    fun setHoldSpeedTwoFinger(v: Float) { viewModelScope.launch { repo.setHoldSpeedTwoFinger(v) } }
    fun setSubtitleStyle(s: SubtitleStyle) { viewModelScope.launch { repo.setSubtitleStyle(s) } }
    fun setSubtitleSize(v: Float) { viewModelScope.launch { repo.setSubtitleSizeFraction(v) } }
    fun setSubtitlePosition(v: Float) { viewModelScope.launch { repo.setSubtitleBottomPaddingFraction(v) } }

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
