// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.videoplayer.core.model.MediaItem
import kotlin.math.absoluteValue

const val DIR_MIME = "vnd.android.document/directory"

data class SafEntry(
    val documentId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

fun safMediaId(uri: String): Long = -(uri.hashCode().toLong().absoluteValue + 1)

fun walkSafTree(
    rootDocumentId: String,
    rootName: String,
    listChildren: (documentId: String) -> List<SafEntry>,
): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    val queue = ArrayDeque<Pair<String, String>>()
    queue.add(rootDocumentId to rootName)
    while (queue.isNotEmpty()) {
        val (docId, path) = queue.removeFirst()
        for (child in listChildren(docId)) {
            when {
                child.mimeType == DIR_MIME ->
                    queue.add(child.documentId to "$path/${child.displayName}")
                child.mimeType.startsWith("video/") ->
                    out.add(
                        MediaItem(
                            id = safMediaId(child.uri),
                            uri = child.uri,
                            displayName = child.displayName,
                            folderPath = path,
                            durationMs = 0,
                            sizeBytes = child.sizeBytes,
                            dateAddedSec = child.lastModifiedMs / 1000,
                        )
                    )
            }
        }
    }
    return out
}
