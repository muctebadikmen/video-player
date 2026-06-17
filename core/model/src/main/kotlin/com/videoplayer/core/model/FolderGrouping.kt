package com.videoplayer.core.model

/**
 * Groups a flat list of [MediaItem]s into [MediaFolder]s.
 *
 * - Items are grouped by [MediaItem.folderPath].
 * - Within each folder, items are sorted by [MediaItem.displayName] case-insensitively.
 * - Folders with no items are dropped (cannot occur from valid input but guarded for safety).
 * - The returned list is sorted by [MediaFolder.name] case-insensitively.
 */
fun groupIntoFolders(items: List<MediaItem>): List<MediaFolder> {
    return items
        .groupBy { it.folderPath }
        .mapNotNull { (folderPath, folderItems) ->
            if (folderItems.isEmpty()) return@mapNotNull null
            val name = folderPath.substringAfterLast('/').ifEmpty { folderPath }
            MediaFolder(
                path = folderPath,
                name = name,
                items = folderItems.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
