package com.videoplayer.app.data.memory

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes a small, scaled-down video frame as a thumbnail, off the main thread, with a
 * byte-budgeted in-memory LRU cache keyed by URI. Uses the built-in [MediaMetadataRetriever]
 * (no image-library dependency). Frames are decoded at thumbnail size (not native resolution)
 * and the cache is bounded by bytes, so a library of high-res clips cannot exhaust the heap.
 * Concurrent decodes are bounded so fast scrolling can't flood the IO pool, and an in-flight
 * decode bails early if its coroutine was cancelled (item scrolled off-screen). Returns null
 * if the frame can't be read.
 */
object ThumbnailLoader {
    private const val FRAME_TIME_US = 1_000_000L // ~1s in — usually past black intros
    private const val TARGET_W = 320
    private const val TARGET_H = 180

    // Byte-budgeted cache (~1/8 of the heap), measured in KB via sizeOf.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // Bound concurrent decodes so a fast scroll through a large grid can't flood Dispatchers.IO.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    suspend fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        coroutineContext.ensureActive()
        val bitmap = withContext(decodeDispatcher) {
            coroutineContext.ensureActive()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_W, TARGET_H,
                    )
                } else {
                    retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { full ->
                            val scaled = Bitmap.createScaledBitmap(full, TARGET_W, TARGET_H, true)
                            if (scaled !== full) full.recycle()
                            scaled
                        }
                }
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
