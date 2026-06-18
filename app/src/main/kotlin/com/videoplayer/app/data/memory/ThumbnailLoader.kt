package com.videoplayer.app.data.memory

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a representative video frame as a thumbnail, off the main thread, with a small
 * in-memory LRU cache keyed by URI. Uses the built-in [MediaMetadataRetriever] — no image
 * library dependency. Returns null if the frame can't be read.
 */
object ThumbnailLoader {
    private const val MAX_CACHE_ENTRIES = 256
    private const val FRAME_TIME_US = 1_000_000L // 1s in — usually past black intros

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}

    suspend fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (bitmap != null) cache.put(uri, bitmap)
        return bitmap
    }
}

/** Loads (and caches) the thumbnail for [uri], returning null until it is ready or if it fails. */
@Composable
fun rememberThumbnail(uri: String): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bitmap = ThumbnailLoader.load(context, uri) }
    return bitmap
}
