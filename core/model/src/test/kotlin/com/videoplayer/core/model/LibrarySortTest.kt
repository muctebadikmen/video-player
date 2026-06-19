// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibrarySortTest {
    private fun item(name: String, date: Long = 0, dur: Long = 0) =
        MediaItem(name.hashCode().toLong(), "uri/$name", name, "/f", dur, 0, date)

    @Test fun `sortItems by name ascending is case-insensitive`() {
        val r = sortItems(listOf(item("banana"), item("Apple"), item("cherry")), SortKey.NAME, SortOrder.ASC)
        assertThat(r.map { it.displayName }).containsExactly("Apple", "banana", "cherry").inOrder()
    }
    @Test fun `sortItems by name descending`() {
        val r = sortItems(listOf(item("a"), item("b"), item("c")), SortKey.NAME, SortOrder.DESC)
        assertThat(r.map { it.displayName }).containsExactly("c", "b", "a").inOrder()
    }
    @Test fun `sortItems by date added ascending`() {
        val r = sortItems(listOf(item("a", date = 30), item("b", date = 10), item("c", date = 20)), SortKey.DATE_ADDED, SortOrder.ASC)
        assertThat(r.map { it.displayName }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `sortItems by duration descending`() {
        val r = sortItems(listOf(item("a", dur = 100), item("b", dur = 300), item("c", dur = 200)), SortKey.DURATION, SortOrder.DESC)
        assertThat(r.map { it.displayName }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `searchItems matches displayName case-insensitively`() {
        val r = searchItems(listOf(item("Holiday.mp4"), item("work.mkv"), item("HOLIDAY2.mp4")), "holiday")
        assertThat(r.map { it.displayName }).containsExactly("Holiday.mp4", "HOLIDAY2.mp4")
    }
    @Test fun `searchItems blank query returns all`() {
        val all = listOf(item("a"), item("b"))
        assertThat(searchItems(all, "   ")).isEqualTo(all)
    }
    @Test fun `allVideos flattens folders preserving folder order`() {
        val f1 = MediaFolder("/x", "x", listOf(item("a"), item("b")))
        val f2 = MediaFolder("/y", "y", listOf(item("c")))
        assertThat(allVideos(listOf(f1, f2)).map { it.displayName }).containsExactly("a", "b", "c").inOrder()
    }
    @Test fun `sortFoldersBy sorts each folder's items but keeps folders by name`() {
        val f1 = MediaFolder("/y", "y", listOf(item("b"), item("a")))
        val f2 = MediaFolder("/x", "x", listOf(item("d"), item("c")))
        val r = sortFoldersBy(listOf(f1, f2), SortKey.NAME, SortOrder.ASC)
        assertThat(r.map { it.name }).containsExactly("x", "y").inOrder()
        assertThat(r.first { it.name == "x" }.items.map { it.displayName }).containsExactly("c", "d").inOrder()
    }
    @Test fun `nextInFolder returns following item`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertThat(nextInFolder(items, "uri/b")?.displayName).isEqualTo("c")
    }
    @Test fun `nextInFolder returns null for last item`() {
        val items = listOf(item("a"), item("b"))
        assertThat(nextInFolder(items, "uri/b")).isNull()
    }
    @Test fun `nextInFolder returns null when uri not present`() {
        assertThat(nextInFolder(listOf(item("a")), "uri/zzz")).isNull()
    }
}