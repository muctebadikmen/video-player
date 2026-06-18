package com.videoplayer.core.model

/** What to order library items by. */
enum class SortKey { NAME, DATE_ADDED, DURATION }

/** Ordering direction. */
enum class SortOrder { ASC, DESC }

/** Returns [items] ordered by [key] in [order]. Name sort is case-insensitive. */
fun sortItems(items: List<MediaItem>, key: SortKey, order: SortOrder): List<MediaItem> {
    val base: Comparator<MediaItem> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortKey.DATE_ADDED -> compareBy { it.dateAddedSec }
        SortKey.DURATION -> compareBy { it.durationMs }
    }
    val comparator = if (order == SortOrder.ASC) base else base.reversed()
    return items.sortedWith(comparator)
}

/** Sorts each folder's items by [key]/[order]; the folder list itself stays ordered by name. */
fun sortFoldersBy(folders: List<MediaFolder>, key: SortKey, order: SortOrder): List<MediaFolder> =
    folders
        .map { it.copy(items = sortItems(it.items, key, order)) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** Case-insensitive substring match on [MediaItem.displayName]. Blank query returns [items] unchanged. */
fun searchItems(items: List<MediaItem>, query: String): List<MediaItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { it.displayName.contains(q, ignoreCase = true) }
}

/** Flattens all folders' items into one list, preserving folder then item order. */
fun allVideos(folders: List<MediaFolder>): List<MediaItem> = folders.flatMap { it.items }

/** The item after the one with [currentUri] in [folderItems], or null if it is last / not found. */
fun nextInFolder(folderItems: List<MediaItem>, currentUri: String): MediaItem? {
    val idx = folderItems.indexOfFirst { it.uri == currentUri }
    if (idx < 0 || idx >= folderItems.lastIndex) return null
    return folderItems[idx + 1]
}
