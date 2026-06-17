package com.videoplayer.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.MediaRepository
import com.videoplayer.core.model.groupIntoFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android [MediaRepository] implementation backed by the system [MediaStore].
 *
 * Call [refresh] to populate / re-populate the flow. Runtime READ permission must be
 * granted by the caller before [refresh] is invoked (permission request UI is a separate task).
 */
class MediaStoreRepository(private val context: Context) : MediaRepository {

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())

    override fun observeFolders(): Flow<List<MediaFolder>> = _folders.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val rows = queryMediaStore()
        _folders.value = groupIntoFolders(rows)
    }

    private fun queryMediaStore(): List<MediaItem> {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )

        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,  // selection
            null,  // selectionArgs
            null,  // sortOrder — groupIntoFolders handles sorting
        ) ?: return emptyList()

        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val bucketCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val displayName = c.getString(nameCol) ?: ""
                    val data = c.getString(dataCol) ?: ""
                    // DATA is deprecated and may be empty under scoped storage (API 29+).
                    // Prefer its parent dir when present; otherwise fall back to the
                    // bucket display name so videos still group under a meaningful folder.
                    val parentFromData = data.takeIf { it.isNotEmpty() }?.let { File(it).parent }
                    val bucketName = if (bucketCol >= 0) c.getString(bucketCol) else null
                    val folderPath = parentFromData ?: bucketName ?: ""
                    val durationMs = c.getLong(durationCol)
                    val sizeBytes = c.getLong(sizeCol)
                    val dateAddedSec = c.getLong(dateCol)
                    val contentUri = ContentUris.withAppendedId(uri, id).toString()

                    add(
                        MediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = displayName,
                            folderPath = folderPath,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            dateAddedSec = dateAddedSec,
                        )
                    )
                }
            }
        }
    }
}
