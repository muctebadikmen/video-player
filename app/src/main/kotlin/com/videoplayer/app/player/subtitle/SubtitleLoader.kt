// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import com.videoplayer.core.playback.SubtitleCue
import com.videoplayer.core.playback.parseSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a subtitle file via ContentResolver and parses it to cues using the tolerant
 * core parser (handles both SRT and VTT). Any failure (missing file, unreadable,
 * revoked permission) logs and returns an empty list — never throws.
 */
object SubtitleLoader {
    private const val TAG = "SubtitleLoader"

    suspend fun load(context: Context, uri: String): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(Uri.parse(uri))
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: return@withContext emptyList()
                parseSubtitles(text)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load subtitle: $uri", e)
                emptyList()
            }
        }
}