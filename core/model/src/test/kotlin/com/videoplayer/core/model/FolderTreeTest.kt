// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private fun item(path: String, name: String) =
        MediaItem(id = name.hashCode().toLong(), uri = "u/$path/$name", displayName = name,
            folderPath = path, durationMs = 0, sizeBytes = 0, dateAddedSec = 0)

    private fun folder(path: String, vararg names: String) =
        MediaFolder(path = path, name = path.substringAfterLast('/'),
            items = names.map { item(path, it) })

    @Test fun empty() {
        assertEquals(emptyList(), buildFolderTree(emptyList()))
    }

    @Test fun singleFolderBecomesRoot() {
        val tree = buildFolderTree(listOf(folder("Movies", "a.mkv")))
        assertEquals(1, tree.size)
        assertEquals("Movies", tree[0].name)
        assertEquals(1, tree[0].directVideoCount)
        assertEquals(1, tree[0].totalVideoCount)
        assertEquals(emptyList(), tree[0].children)
    }

    @Test fun nestedSubfolderNestsUnderParent() {
        val tree = buildFolderTree(listOf(
            folder("Movies", "a.mkv"),
            folder("Movies/Action", "b.mkv", "c.mkv"),
        ))
        assertEquals(1, tree.size)
        val movies = tree[0]
        assertEquals("Movies", movies.name)
        assertEquals(1, movies.directVideoCount)
        assertEquals(3, movies.totalVideoCount)
        assertEquals(1, movies.children.size)
        assertEquals("Action", movies.children[0].name)
        assertEquals(2, movies.children[0].directVideoCount)
    }

    @Test fun intermediateSegmentWithoutVideosBecomesNode() {
        val tree = buildFolderTree(listOf(folder("A/B/C", "x.mkv")))
        assertEquals("A", tree[0].name)
        assertEquals(0, tree[0].directVideoCount)
        assertEquals(1, tree[0].totalVideoCount)
        assertEquals("B", tree[0].children[0].name)
        assertEquals("C", tree[0].children[0].children[0].name)
        assertEquals(1, tree[0].children[0].children[0].directVideoCount)
    }

    @Test fun siblingsSortedCaseInsensitively() {
        val tree = buildFolderTree(listOf(
            folder("zebra", "a"), folder("Apple", "b"), folder("mango", "c"),
        ))
        assertEquals(listOf("Apple", "mango", "zebra"), tree.map { it.name })
    }
}
