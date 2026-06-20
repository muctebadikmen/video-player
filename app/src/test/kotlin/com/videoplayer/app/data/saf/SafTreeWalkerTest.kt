// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafTreeWalkerTest {

    private fun dir(id: String, name: String) =
        SafEntry(id, "uri://$id", name, DIR_MIME, 0, 0)

    private fun video(id: String, name: String, size: Long = 10, modified: Long = 7000) =
        SafEntry(id, "uri://$id", name, "video/mp4", size, modified)

    /** root/ has a.mp4; root/sub/ has b.mp4; root/.hidden/ has c.mp4 */
    private val tree: Map<String, List<SafEntry>> = mapOf(
        "root" to listOf(video("a", "a.mp4"), dir("sub", "sub"), dir("hid", ".hidden")),
        "sub" to listOf(video("b", "b.mp4")),
        "hid" to listOf(video("c", "c.mp4")),
    )

    private fun listChildren(id: String): List<SafEntry> = tree[id].orEmpty()

    @Test
    fun `walks all subfolders recursively including hidden`() {
        val items = walkSafTree("root", "Movies", ::listChildren)
        assertThat(items.map { it.displayName }).containsExactly("a.mp4", "b.mp4", "c.mp4")
    }

    @Test
    fun `folderPath reflects nesting from root name`() {
        val items = walkSafTree("root", "Movies", ::listChildren).associateBy { it.displayName }
        assertThat(items["a.mp4"]!!.folderPath).isEqualTo("Movies")
        assertThat(items["b.mp4"]!!.folderPath).isEqualTo("Movies/sub")
        assertThat(items["c.mp4"]!!.folderPath).isEqualTo("Movies/.hidden")
    }

    @Test
    fun `non-video files are ignored`() {
        val withDoc = tree + ("root" to (tree["root"]!! + SafEntry("d", "uri://d", "notes.txt", "text/plain", 1, 1)))
        val items = walkSafTree("root", "Movies") { withDoc[it].orEmpty() }
        assertThat(items.map { it.displayName }).doesNotContain("notes.txt")
    }

    @Test
    fun `maps metadata onto MediaItem`() {
        val items = walkSafTree("root", "Movies", ::listChildren)
        val a = items.first { it.displayName == "a.mp4" }
        assertThat(a.uri).isEqualTo("uri://a")
        assertThat(a.dateAddedSec).isEqualTo(7L)   // 7000ms / 1000
        assertThat(a.id).isLessThan(0L)            // never collides with MediaStore
        assertThat(a.durationMs).isEqualTo(0L)
    }

    @Test
    fun `safMediaId is deterministic and negative`() {
        assertThat(safMediaId("uri://x")).isEqualTo(safMediaId("uri://x"))
        assertThat(safMediaId("uri://x")).isLessThan(0L)
    }
}
