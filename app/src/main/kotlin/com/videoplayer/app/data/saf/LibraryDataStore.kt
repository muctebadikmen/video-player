// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(name = "library_sources")
