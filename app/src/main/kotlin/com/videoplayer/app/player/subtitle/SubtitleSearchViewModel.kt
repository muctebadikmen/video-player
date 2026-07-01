// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoplayer.app.data.memory.settingsDataStore
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesClient
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesCredentialsRepository
import com.videoplayer.app.data.opensubtitles.OsError
import com.videoplayer.app.data.opensubtitles.OsResult
import com.videoplayer.core.playback.SubtitleSearchResult
import com.videoplayer.core.playback.rankSubtitleResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object NotLoggedIn : SearchUiState
    data object Loading : SearchUiState
    data class Results(val items: List<SubtitleSearchResult>, val remaining: Int) : SearchUiState
    data object Empty : SearchUiState
    data object Offline : SearchUiState
    data object QuotaExhausted : SearchUiState
    data class Error(val message: String) : SearchUiState
}

/**
 * Map a client error to a user-facing state. For HTTP errors it surfaces the server's real reason
 * (the X-Reason header the client now reads) instead of a meaningless "Server error (code)", falling
 * back to the code only when no message is available. Pure + testable.
 */
internal fun mapSearchError(e: OsError): SearchUiState = when (e) {
    is OsError.QuotaExhausted -> SearchUiState.QuotaExhausted
    is OsError.Offline -> SearchUiState.Offline
    is OsError.NotLoggedIn -> SearchUiState.NotLoggedIn
    is OsError.Http -> if (e.code == 401) SearchUiState.Error("Session expired — log in again in Settings")
        else SearchUiState.Error(e.message.ifBlank { "Server error (${e.code})" })
    is OsError.Unexpected -> SearchUiState.Error(e.message)
}

class SubtitleSearchViewModel(
    private val osRepo: OpenSubtitlesCredentialsRepository,
    private val versionName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.NotLoggedIn)
    val uiState: StateFlow<SearchUiState> = _uiState

    fun search(context: Context, videoUri: String, videoFileName: String) {
        viewModelScope.launch {
            val creds = osRepo.credentials.first()
            val token = creds.token
            if (token == null || creds.apiKey.isBlank()) { _uiState.value = SearchUiState.NotLoggedIn; return@launch }
            _uiState.value = SearchUiState.Loading

            val client = OpenSubtitlesClient(versionName = versionName, baseUrl = creds.baseUrl)
            val hash = MovieHasher.hash(context, videoUri) // may be null → filename-only
            val queryName = videoFileName.substringBeforeLast('.')
            when (val r = client.search(creds.apiKey, token, hash, queryName, creds.favoriteLanguages)) {
                is OsResult.Success -> {
                    val ranked = rankSubtitleResults(r.value, creds.favoriteLanguages)
                    _uiState.value = if (ranked.isEmpty()) SearchUiState.Empty
                        else SearchUiState.Results(ranked, creds.remaining)
                }
                is OsResult.Failure -> _uiState.value = mapSearchError(r.error)
            }
        }
    }

    fun download(
        context: Context,
        result: SubtitleSearchResult,
        videoFileName: String,
        onSaved: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val creds = osRepo.credentials.first()
            val token = creds.token
            if (token == null) { _uiState.value = SearchUiState.NotLoggedIn; onSaved(null); return@launch }
            val client = OpenSubtitlesClient(versionName = versionName, baseUrl = creds.baseUrl)
            when (val r = client.download(creds.apiKey, token, result.fileId)) {
                is OsResult.Success -> {
                    osRepo.setRemaining(r.value.remaining)
                    val uri = SubtitleDownloader.save(context, videoFileName, result.language, r.value.bytes)
                    // reflect the spent quota in the open results sheet
                    (_uiState.value as? SearchUiState.Results)?.let {
                        _uiState.value = it.copy(remaining = r.value.remaining)
                    }
                    onSaved(uri)
                }
                is OsResult.Failure -> { _uiState.value = mapSearchError(r.error); onSaved(null) }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                val versionName = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.3.0"
                SubtitleSearchViewModel(OpenSubtitlesCredentialsRepository(app.settingsDataStore), versionName)
            }
        }
    }
}
