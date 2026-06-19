// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.opensubtitles

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persisted OpenSubtitles account state. The password is NEVER stored — only the session token. */
data class OpenSubtitlesCredentials(
    val apiKey: String,
    val username: String,
    val token: String?,
    val baseUrl: String,
    val allowedDownloads: Int,
    val remaining: Int,
    val level: String?,
    val favoriteLanguages: List<String>,
)

class OpenSubtitlesCredentialsRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val API_KEY = stringPreferencesKey("os_api_key")
        val USERNAME = stringPreferencesKey("os_username")
        val TOKEN = stringPreferencesKey("os_token")
        val BASE_URL = stringPreferencesKey("os_base_url")
        val ALLOWED = intPreferencesKey("os_allowed_downloads")
        val REMAINING = intPreferencesKey("os_remaining")
        val LEVEL = stringPreferencesKey("os_level")
        val FAV_LANGS = stringPreferencesKey("os_fav_langs")
    }

    private val defaultBaseUrl = "https://api.opensubtitles.com/api/v1"

    val credentials: Flow<OpenSubtitlesCredentials> = dataStore.data.map { p ->
        OpenSubtitlesCredentials(
            apiKey = p[Keys.API_KEY] ?: "",
            username = p[Keys.USERNAME] ?: "",
            token = p[Keys.TOKEN],
            baseUrl = p[Keys.BASE_URL] ?: defaultBaseUrl,
            allowedDownloads = p[Keys.ALLOWED] ?: 0,
            remaining = p[Keys.REMAINING] ?: 0,
            level = p[Keys.LEVEL],
            favoriteLanguages = (p[Keys.FAV_LANGS] ?: "tr,en")
                .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
        )
    }

    suspend fun setApiKey(key: String) = dataStore.edit { it[Keys.API_KEY] = key.trim() }.let {}
    suspend fun setUsername(name: String) = dataStore.edit { it[Keys.USERNAME] = name.trim() }.let {}

    suspend fun saveSession(token: String, baseUrl: String, allowedDownloads: Int, level: String?) {
        dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.BASE_URL] = baseUrl
            it[Keys.ALLOWED] = allowedDownloads
            it[Keys.REMAINING] = allowedDownloads
            if (level != null) it[Keys.LEVEL] = level
        }
    }

    suspend fun setRemaining(remaining: Int) = dataStore.edit { it[Keys.REMAINING] = remaining }.let {}

    suspend fun setFavoriteLanguages(langsCsv: String) =
        dataStore.edit { it[Keys.FAV_LANGS] = langsCsv }.let {}

    suspend fun logout() {
        dataStore.edit {
            it.remove(Keys.TOKEN); it.remove(Keys.BASE_URL); it.remove(Keys.LEVEL)
            it[Keys.ALLOWED] = 0; it[Keys.REMAINING] = 0
        }
    }
}
