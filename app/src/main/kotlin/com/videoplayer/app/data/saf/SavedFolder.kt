// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A folder the user added via SAF, persisted across launches. */
@Serializable
data class SavedFolder(val treeUri: String, val displayName: String)

/** Which library source is active: the global MediaStore view, or one saved folder. */
sealed interface LibrarySourceId {
    data object Global : LibrarySourceId
    data class Folder(val treeUri: String) : LibrarySourceId
}

private val json = Json { ignoreUnknownKeys = true }
private const val GLOBAL_RAW = "GLOBAL"

fun encodeSavedFolders(folders: List<SavedFolder>): String = json.encodeToString(folders)

fun decodeSavedFolders(jsonStr: String?): List<SavedFolder> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<SavedFolder>>(jsonStr) }.getOrDefault(emptyList())
}

fun encodeSourceId(id: LibrarySourceId): String = when (id) {
    is LibrarySourceId.Global -> GLOBAL_RAW
    is LibrarySourceId.Folder -> id.treeUri
}

fun decodeSourceId(raw: String?): LibrarySourceId =
    if (raw.isNullOrBlank() || raw == GLOBAL_RAW) LibrarySourceId.Global else LibrarySourceId.Folder(raw)
