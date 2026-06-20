// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaRepository
import com.videoplayer.core.model.groupIntoFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [MediaRepository] backed by a SAF document tree. Walks the picked folder and all of its
 * subfolders (including hidden ones) via [DocumentsContract] child-document queries — fast,
 * no per-file DocumentFile objects — and groups the result with the shared [groupIntoFolders].
 */
class SafFolderRepository(
    private val context: Context,
    private val treeUri: Uri,
) : MediaRepository {

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    override fun observeFolders(): Flow<List<MediaFolder>> = _folders.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootDisplayName(rootDocId)
        _folders.value = groupIntoFolders(walkSafTree(rootDocId, rootName, ::listChildren))
    }

    private fun rootDisplayName(rootDocId: String): String {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        context.contentResolver.query(
            rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        return rootDocId.substringAfterLast('/').substringAfterLast(':')
    }

    private fun listChildren(parentDocId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    add(
                        SafEntry(
                            documentId = docId,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            displayName = c.getString(1) ?: "",
                            mimeType = c.getString(2) ?: "",
                            sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                            lastModifiedMs = if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}
