// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibrarySourceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStore(): LibrarySourceStore {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.newFolder(), "src.preferences_pb") }
        )
        return LibrarySourceStore(ds)
    }

    @Test
    fun `defaults are empty list and global`() = runTest {
        val store = newStore()
        assertThat(store.savedFolders.first()).isEmpty()
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Global)
    }

    @Test
    fun `addFolder persists and dedupes by treeUri`() = runTest {
        val store = newStore()
        store.addFolder(SavedFolder("content://tree/a", "Anime"))
        store.addFolder(SavedFolder("content://tree/a", "Anime again"))
        assertThat(store.savedFolders.first()).containsExactly(SavedFolder("content://tree/a", "Anime"))
    }

    @Test
    fun `setActive then removeFolder resets active to global`() = runTest {
        val store = newStore()
        store.addFolder(SavedFolder("content://tree/a", "Anime"))
        store.setActive(LibrarySourceId.Folder("content://tree/a"))
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Folder("content://tree/a"))
        store.removeFolder("content://tree/a")
        assertThat(store.savedFolders.first()).isEmpty()
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Global)
    }
}
