package com.videoplayer.app.player.subtitle

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.videoplayer.core.playback.findSiblingSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort discovery of same-folder SRT/VTT siblings for a video, via MediaStore.Files.
 * On scoped storage (API 29+) only MediaStore-indexed subtitle files are visible, so this may
 * return nothing even when a sibling exists on disk — the reliable path is the manual SAF pick.
 * Results are restricted to the video's own folder (by folder name) when the file's path is known.
 * Any failure returns an empty list.
 */
object SiblingSubtitleScanner {
    private const val TAG = "SiblingSubtitleScanner"

    @Suppress("DEPRECATION") // DATA column used only to compare folder names; tolerated as best-effort.
    suspend fun scan(
        context: Context,
        videoFolderName: String,
        videoFileName: String,
    ): List<SubtitleOption> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleOption>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
            )
            val selection =
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%.srt", "%.vtt")

            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                data class Row(val id: Long, val name: String, val folder: String?)
                val rows = mutableListOf<Row>()
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val data = if (dataCol >= 0) c.getString(dataCol) else null
                    val folder = data?.substringBeforeLast('/')?.substringAfterLast('/')
                    rows.add(Row(id, name, folder))
                }

                val matchingNames = findSiblingSubtitles(videoFileName, rows.map { it.name }).toSet()
                val seen = mutableSetOf<String>()
                for (row in rows) {
                    if (row.name !in matchingNames) continue
                    // When we know the folder, require it to match the video's folder.
                    if (row.folder != null && row.folder != videoFolderName) continue
                    val uri = ContentUris.withAppendedId(collection, row.id).toString()
                    if (seen.add(uri)) results.add(SubtitleOption(uri, row.name))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sibling subtitle scan failed", e)
        }
        results
    }
}
