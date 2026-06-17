package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderGroupingTest {

    private fun item(
        id: Long,
        displayName: String,
        folderPath: String,
    ) = MediaItem(
        id = id,
        uri = "content://media/$id",
        displayName = displayName,
        folderPath = folderPath,
        durationMs = 1000L,
        sizeBytes = 512L,
        dateAddedSec = 0L,
    )

    @Test
    fun `empty input yields empty list`() {
        val result = groupIntoFolders(emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `groups items by folder and sorts folders case-insensitively`() {
        val items = listOf(
            item(1, "b.mp4", "/sdcard/movies"),
            item(2, "a.mp4", "/sdcard/movies"),
            item(3, "z.mp4", "/sdcard/clips"),
            item(4, "m.mp4", "/sdcard/clips"),
        )

        val result = groupIntoFolders(items)

        // Two folders, sorted case-insensitively: "clips" before "movies"
        assertThat(result.map { it.name }).containsExactly("clips", "movies").inOrder()
    }

    @Test
    fun `items within each folder are sorted by displayName case-insensitively`() {
        val items = listOf(
            item(1, "b.mp4", "/sdcard/movies"),
            item(2, "a.mp4", "/sdcard/movies"),
            item(3, "z.mp4", "/sdcard/clips"),
            item(4, "m.mp4", "/sdcard/clips"),
        )

        val result = groupIntoFolders(items)
        val clipsItems = result.first { it.name == "clips" }.items
        val moviesItems = result.first { it.name == "movies" }.items

        assertThat(clipsItems.map { it.displayName }).containsExactly("m.mp4", "z.mp4").inOrder()
        assertThat(moviesItems.map { it.displayName }).containsExactly("a.mp4", "b.mp4").inOrder()
    }

    @Test
    fun `name is last path segment`() {
        val items = listOf(item(1, "video.mp4", "/sdcard/DCIM/Camera"))
        val result = groupIntoFolders(items)
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("Camera")
        assertThat(result[0].path).isEqualTo("/sdcard/DCIM/Camera")
    }

    @Test
    fun `name falls back to full path when no slash`() {
        val items = listOf(item(1, "video.mp4", "rootfolder"))
        val result = groupIntoFolders(items)
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("rootfolder")
    }

    @Test
    fun `items in same folder are grouped together`() {
        val items = listOf(
            item(1, "a.mp4", "/videos"),
            item(2, "b.mp4", "/videos"),
            item(3, "c.mp4", "/videos"),
        )
        val result = groupIntoFolders(items)
        assertThat(result).hasSize(1)
        assertThat(result[0].videoCount).isEqualTo(3)
    }

    @Test
    fun `case-insensitive folder sort places lowercase before uppercase`() {
        val items = listOf(
            item(1, "a.mp4", "/Beta"),
            item(2, "b.mp4", "/alpha"),
        )
        val result = groupIntoFolders(items)
        // "alpha" < "Beta" case-insensitively
        assertThat(result.map { it.name }).containsExactly("alpha", "Beta").inOrder()
    }
}
