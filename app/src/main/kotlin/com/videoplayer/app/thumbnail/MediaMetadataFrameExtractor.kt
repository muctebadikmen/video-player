// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.videoplayer.core.model.FrameStats
import com.videoplayer.core.model.THUMBNAIL_SAMPLE_FRACTIONS
import com.videoplayer.core.model.luminanceStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * MediaMetadataRetriever-backed frame extraction. Sampling uses tiny bitmaps (fast); saving writes a
 * display-size JPEG to filesDir/thumbnails. Each retriever is released in a finally; all I/O on IO.
 */
class MediaMetadataFrameExtractor(private val context: Context) : FrameExtractor {

    override suspend fun sampleStats(mediaUri: String, durationMs: Long): List<FrameStats> =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0L) return@withContext emptyList()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(mediaUri))
                THUMBNAIL_SAMPLE_FRACTIONS.map { frac ->
                    val ms = (durationMs * frac).toLong()
                    val bmp = frameAt(retriever, ms, SAMPLE_W, SAMPLE_H)
                    if (bmp == null) {
                        FrameStats(0.0, 0.0)
                    } else {
                        val pixels = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        bmp.recycle()
                        luminanceStats(pixels)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }

    override suspend fun extractAndSave(mediaUri: String, frameMs: Long): String? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val bmp = try {
                retriever.setDataSource(context, Uri.parse(mediaUri))
                frameAt(retriever, frameMs, SAVE_W, SAVE_H)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            } ?: return@withContext null

            try {
                val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
                val file = File(dir, thumbnailFileName(mediaUri, System.currentTimeMillis()))
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            } catch (_: Exception) {
                null
            } finally {
                bmp.recycle()
            }
        }

    private fun frameAt(r: MediaMetadataRetriever, ms: Long, w: Int, h: Int): Bitmap? {
        val us = ms * 1000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            val full = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
        }
    }

    private companion object {
        const val SAMPLE_W = 80; const val SAMPLE_H = 45
        const val SAVE_W = 640; const val SAVE_H = 360
    }
}
