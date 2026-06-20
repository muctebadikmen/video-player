// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedFolderTest {

    @Test
    fun `round-trips a folder list through json`() {
        val list = listOf(SavedFolder("content://tree/a", "Anime"), SavedFolder("content://tree/b", ".private"))
        assertThat(decodeSavedFolders(encodeSavedFolders(list))).isEqualTo(list)
    }

    @Test
    fun `decode tolerates null and garbage`() {
        assertThat(decodeSavedFolders(null)).isEmpty()
        assertThat(decodeSavedFolders("")).isEmpty()
        assertThat(decodeSavedFolders("not json")).isEmpty()
    }

    @Test
    fun `source id round-trips`() {
        assertThat(decodeSourceId(encodeSourceId(LibrarySourceId.Global))).isEqualTo(LibrarySourceId.Global)
        val f = LibrarySourceId.Folder("content://tree/a")
        assertThat(decodeSourceId(encodeSourceId(f))).isEqualTo(f)
    }

    @Test
    fun `source id defaults to global`() {
        assertThat(decodeSourceId(null)).isEqualTo(LibrarySourceId.Global)
        assertThat(decodeSourceId("")).isEqualTo(LibrarySourceId.Global)
        assertThat(decodeSourceId("GLOBAL")).isEqualTo(LibrarySourceId.Global)
    }
}
