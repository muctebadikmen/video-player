// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.intent

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ExternalVideo"

/**
 * The data [Uri] of an inbound `ACTION_VIEW` intent (a file manager "Open with"
 * or a link tap), or null when the intent is not a VIEW with data (e.g. the
 * LAUNCHER MAIN intent). The caller resolves/reads it within the granted
 * permission of this launch — we do NOT take a persistable grant for VIEW.
 */
fun externalVideoUriFromIntent(intent: Intent?): Uri? {
    if (intent == null) return null
    if (intent.action != Intent.ACTION_VIEW) return null
    return intent.data
}

/**
 * Build a minimal [MediaItem] for a foreign [uri] handed to us by another app.
 *
 * For `content://` we query [OpenableColumns] for the display name + size; for
 * `file://` we derive the name from the last path segment and size from the
 * file. Duration comes from [MediaMetadataRetriever] (best-effort). All work is
 * off the main thread. Any failure degrades gracefully — this never throws, so a
 * weird provider can't crash the open-with flow.
 */
suspend fun synthesizeMediaItem(context: Context, uri: Uri): MediaItem =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size = 0L

        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                runCatching {
                    resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                            if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                            if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol)
                        }
                    }
                }.onFailure { Log.w(TAG, "OpenableColumns query failed for $uri", it) }
            }
            ContentResolver.SCHEME_FILE -> {
                name = uri.lastPathSegment
                uri.path?.let { p -> runCatching { File(p).length() }.getOrNull()?.let { size = it } }
            }
            else -> {
                // http/https or other streamable schemes: no local metadata; use the last segment.
                name = uri.lastPathSegment
            }
        }

        val duration = if (uri.scheme == "http" || uri.scheme == "https") {
            0L
        } else runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrElse {
            Log.w(TAG, "Duration probe failed for $uri", it)
            0L
        }

        MediaItem(
            id = uri.hashCode().toLong(),
            uri = uri.toString(),
            displayName = name?.takeIf { it.isNotBlank() } ?: "Video",
            folderPath = "",
            durationMs = duration,
            sizeBytes = size,
            dateAddedSec = System.currentTimeMillis() / 1000L,
        )
    }
