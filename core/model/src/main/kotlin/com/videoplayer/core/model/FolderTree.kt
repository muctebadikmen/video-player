// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

data class FolderNode(
    val name: String,
    val path: String,
    val items: List<MediaItem>,
    val children: List<FolderNode>,
) {
    val directVideoCount: Int get() = items.size
    val totalVideoCount: Int get() = items.size + children.sumOf { it.totalVideoCount }
}

private class MutableNode(val name: String, val path: String) {
    var items: List<MediaItem> = emptyList()
    val children = LinkedHashMap<String, MutableNode>()
    fun toNode(): FolderNode = collapse(
        FolderNode(
            name = name,
            path = path,
            items = items,
            children = children.values
                .map { it.toNode() }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )
    )
}

/** Collapse a single-child chain: a node with no direct items and exactly one child
 *  is merged with that child, joining display names with '/' and keeping the deepest path. */
private fun collapse(node: FolderNode): FolderNode {
    if (node.items.isNotEmpty() || node.children.size != 1) return node
    val child = node.children[0]
    return collapse(
        FolderNode(
            name = "${node.name}/${child.name}",
            path = child.path,
            items = child.items,
            children = child.children,
        )
    )
}

fun buildFolderTree(folders: List<MediaFolder>): List<FolderNode> {
    val roots = LinkedHashMap<String, MutableNode>()
    for (folder in folders) {
        val segments = folder.path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var levelMap = roots
        var accPath = ""
        var node: MutableNode? = null
        for (segment in segments) {
            accPath = if (accPath.isEmpty()) segment else "$accPath/$segment"
            node = levelMap.getOrPut(segment) { MutableNode(segment, accPath) }
            levelMap = node.children
        }
        node?.items = folder.items
    }
    return roots.values
        .map { it.toNode() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
