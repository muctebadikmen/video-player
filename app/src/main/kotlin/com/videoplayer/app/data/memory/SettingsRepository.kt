// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_TWO
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_BOTTOM_PADDING
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION
import com.videoplayer.app.player.gestures.clampHoldSpeed
import com.videoplayer.app.player.gestures.clampSubtitleBottomPadding
import com.videoplayer.app.player.gestures.clampSubtitleSize
import com.videoplayer.app.player.subtitle.SubtitleStyle
import com.videoplayer.app.player.subtitle.subtitleStyleFromName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-wide settings DataStore. One instance per process, keyed off the app context. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global playback defaults (the lowest precedence tier). No settings UI exists yet
 * (P1.H) — these read their defaults today and are forward-ready for a settings screen.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) :
    GridSizePreferences, PlaybackGesturePreferences, SubtitlePreferences {

    private object Keys {
        val RESUME_ENABLED = booleanPreferencesKey("resume_enabled")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val HOLD_SPEED_ONE = floatPreferencesKey("hold_speed_one_finger")
        val HOLD_SPEED_TWO = floatPreferencesKey("hold_speed_two_finger")
        val SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val SUBTITLE_SIZE = floatPreferencesKey("subtitle_size_fraction")
        val SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding_fraction")
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

    override val gridColumns: Flow<Int> = dataStore.data.map { it[Keys.GRID_COLUMNS] ?: 3 }

    override suspend fun setGridColumns(columns: Int) {
        dataStore.edit { it[Keys.GRID_COLUMNS] = columns }
    }

    override val holdSpeedOneFinger: Flow<Float> =
        dataStore.data.map { it[Keys.HOLD_SPEED_ONE] ?: DEFAULT_HOLD_SPEED_ONE }
    override val holdSpeedTwoFinger: Flow<Float> =
        dataStore.data.map { it[Keys.HOLD_SPEED_TWO] ?: DEFAULT_HOLD_SPEED_TWO }

    override suspend fun setHoldSpeedOneFinger(speed: Float) {
        dataStore.edit { it[Keys.HOLD_SPEED_ONE] = clampHoldSpeed(speed) }
    }
    override suspend fun setHoldSpeedTwoFinger(speed: Float) {
        dataStore.edit { it[Keys.HOLD_SPEED_TWO] = clampHoldSpeed(speed) }
    }

    override val subtitleStyle: Flow<SubtitleStyle> =
        dataStore.data.map { subtitleStyleFromName(it[Keys.SUBTITLE_STYLE]) }
    override val subtitleSizeFraction: Flow<Float> =
        dataStore.data.map { it[Keys.SUBTITLE_SIZE] ?: DEFAULT_SUBTITLE_SIZE_FRACTION }
    override val subtitleBottomPaddingFraction: Flow<Float> =
        dataStore.data.map { it[Keys.SUBTITLE_BOTTOM_PADDING] ?: DEFAULT_SUBTITLE_BOTTOM_PADDING }

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        dataStore.edit { it[Keys.SUBTITLE_STYLE] = style.name }
    }
    override suspend fun setSubtitleSizeFraction(fraction: Float) {
        dataStore.edit { it[Keys.SUBTITLE_SIZE] = clampSubtitleSize(fraction) }
    }
    override suspend fun setSubtitleBottomPaddingFraction(fraction: Float) {
        dataStore.edit { it[Keys.SUBTITLE_BOTTOM_PADDING] = clampSubtitleBottomPadding(fraction) }
    }
}
