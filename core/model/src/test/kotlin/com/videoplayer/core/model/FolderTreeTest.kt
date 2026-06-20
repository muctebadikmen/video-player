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

    // Updated: single-child chain now collapses — A/B/C with no items in A or B
    // becomes a single root node named "A/B/C" with path "A/B/C" and 1 direct video.
    @Test fun intermediateSegmentWithoutVideosBecomesNode() {
        val tree = buildFolderTree(listOf(folder("A/B/C", "x.mkv")))
        assertEquals(1, tree.size)
        assertEquals("A/B/C", tree[0].name)
        assertEquals("A/B/C", tree[0].path)
        assertEquals(1, tree[0].directVideoCount)
        assertEquals(1, tree[0].totalVideoCount)
        assertEquals(emptyList(), tree[0].children)
    }

    @Test fun siblingsSortedCaseInsensitively() {
        val tree = buildFolderTree(listOf(
            folder("zebra", "a"), folder("Apple", "b"), folder("mango", "c"),
        ))
        assertEquals(listOf("Apple", "mango", "zebra"), tree.map { it.name })
    }

    // Deep single-child chain (MediaStore-style path) collapses to one node.
    @Test fun deepSingleChildChainCollapsesToOneNode() {
        val tree = buildFolderTree(listOf(
            folder("storage/emulated/0/DCIM/Camera", "vid1.mp4", "vid2.mp4")
        ))
        assertEquals(1, tree.size)
        assertEquals("storage/emulated/0/DCIM/Camera", tree[0].name)
        assertEquals("storage/emulated/0/DCIM/Camera", tree[0].path)
        assertEquals(2, tree[0].directVideoCount)
        assertEquals(2, tree[0].totalVideoCount)
        assertEquals(emptyList(), tree[0].children)
    }

    // Branching is preserved: two-leaf siblings do NOT collapse the branch node.
    @Test fun branchingNodeIsPreserved() {
        val tree = buildFolderTree(listOf(
            folder("Movies/Action", "a.mkv"),
            folder("Movies/Comedy", "b.mkv"),
        ))
        // "Movies" has 2 children, so it must NOT collapse.
        assertEquals(1, tree.size)
        val movies = tree[0]
        assertEquals("Movies", movies.name)
        assertEquals(0, movies.directVideoCount)
        assertEquals(2, movies.totalVideoCount)
        assertEquals(2, movies.children.size)
        assertEquals(listOf("Action", "Comedy"), movies.children.map { it.name })
    }

    // A node that HAS direct items AND one child does NOT collapse into its child.
    @Test fun nodeWithItemsAndOneChildDoesNotCollapse() {
        val tree = buildFolderTree(listOf(
            folder("Parent", "root.mkv"),
            folder("Parent/Child", "child.mkv"),
        ))
        assertEquals(1, tree.size)
        val parent = tree[0]
        assertEquals("Parent", parent.name)
        assertEquals(1, parent.directVideoCount)
        assertEquals(2, parent.totalVideoCount)
        assertEquals(1, parent.children.size)
        assertEquals("Child", parent.children[0].name)
        assertEquals(1, parent.children[0].directVideoCount)
    }

    // Case-insensitive sibling sort at depth > 0.
    @Test fun siblingsSortedCaseInsensitivelyAtDepthGreaterThanZero() {
        val tree = buildFolderTree(listOf(
            folder("Parent/zebra", "a"),
            folder("Parent/Apple", "b"),
            folder("Parent/mango", "c"),
        ))
        // Parent has no direct items but 3 children — does NOT collapse (3 children).
        assertEquals(1, tree.size)
        assertEquals("Parent", tree[0].name)
        assertEquals(listOf("Apple", "mango", "zebra"), tree[0].children.map { it.name })
    }
}
