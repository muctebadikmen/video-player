// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saves a downloaded subtitle into app-private external storage and returns its file:// URI. */
object SubtitleDownloader {
    private const val TAG = "SubtitleDownloader"

    suspend fun save(
        context: Context,
        videoFileName: String,
        language: String,
        bytes: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir("subtitles") ?: return@withContext null
            if (!dir.exists()) dir.mkdirs()
            val stem = videoFileName.substringBeforeLast('.').ifBlank { "subtitle" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val lang = language.ifBlank { "und" }.replace(Regex("[^A-Za-z0-9-]"), "")
            val file = File(dir, "$stem.$lang.srt")
            file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save subtitle", e)
            null
        }
    }
}
