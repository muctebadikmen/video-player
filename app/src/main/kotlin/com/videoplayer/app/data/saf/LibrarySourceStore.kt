// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read/write the user's saved SAF folders and the active source. Faked in tests by InMemorySourceStore. */
interface SourceStore {
    val savedFolders: Flow<List<SavedFolder>>
    val activeSource: Flow<LibrarySourceId>
    suspend fun addFolder(folder: SavedFolder)
    suspend fun removeFolder(treeUri: String)
    suspend fun setActive(id: LibrarySourceId)
}

/** Persists the user's saved SAF folders and which source is currently active. */
class LibrarySourceStore(private val dataStore: DataStore<Preferences>) : SourceStore {

    private object Keys {
        val FOLDERS = stringPreferencesKey("saf_saved_folders")
        val ACTIVE = stringPreferencesKey("saf_active_source")
    }

    override val savedFolders: Flow<List<SavedFolder>> =
        dataStore.data.map { decodeSavedFolders(it[Keys.FOLDERS]) }

    override val activeSource: Flow<LibrarySourceId> =
        dataStore.data.map { decodeSourceId(it[Keys.ACTIVE]) }

    override suspend fun addFolder(folder: SavedFolder) {
        dataStore.edit { prefs ->
            val current = decodeSavedFolders(prefs[Keys.FOLDERS])
            if (current.none { it.treeUri == folder.treeUri }) {
                prefs[Keys.FOLDERS] = encodeSavedFolders(current + folder)
            }
        }
    }

    override suspend fun removeFolder(treeUri: String) {
        dataStore.edit { prefs ->
            val current = decodeSavedFolders(prefs[Keys.FOLDERS])
            prefs[Keys.FOLDERS] = encodeSavedFolders(current.filterNot { it.treeUri == treeUri })
            if (decodeSourceId(prefs[Keys.ACTIVE]) == LibrarySourceId.Folder(treeUri)) {
                prefs[Keys.ACTIVE] = encodeSourceId(LibrarySourceId.Global)
            }
        }
    }

    override suspend fun setActive(id: LibrarySourceId) {
        dataStore.edit { it[Keys.ACTIVE] = encodeSourceId(id) }
    }
}
