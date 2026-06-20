// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-wide settings DataStore. One instance per process, keyed off the app context. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global playback defaults (the lowest precedence tier). No settings UI exists yet
 * (P1.H) — these read their defaults today and are forward-ready for a settings screen.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val RESUME_ENABLED = booleanPreferencesKey("resume_enabled")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
    }

    val resumeEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.RESUME_ENABLED] ?: true }
    val defaultSpeed: Flow<Float> = dataStore.data.map { it[Keys.DEFAULT_SPEED] ?: 1f }

    suspend fun setResumeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RESUME_ENABLED] = enabled }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        dataStore.edit { it[Keys.DEFAULT_SPEED] = speed }
    }

    val backgroundPlaybackEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.BACKGROUND_PLAYBACK] ?: true }

    suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_PLAYBACK] = enabled }
    }

    val gridColumns: Flow<Int> = dataStore.data.map { it[Keys.GRID_COLUMNS] ?: 3 }

    suspend fun setGridColumns(columns: Int) {
        dataStore.edit { it[Keys.GRID_COLUMNS] = columns }
    }
}