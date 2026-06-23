// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import android.content.Context
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.VideoThumbnailDao
import com.videoplayer.app.data.memory.VideoThumbnailEntity
import com.videoplayer.core.model.THUMBNAIL_SAMPLE_FRACTIONS
import com.videoplayer.core.model.pickBestFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File

interface ThumbnailController {
    fun observeAll(): Flow<List<VideoThumbnailEntity>>
    fun ensureAutoThumbnail(mediaUri: String, durationMs: Long)
    fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long)
    fun resetToAuto(mediaUri: String)
}

/**
 * Orchestrates auto-default + manual thumbnails. Auto compute is lazy, deduped (in-flight set),
 * and bounded (semaphore) so scrolling a big library never spawns dozens of retrievers. Manual sets
 * extract+save a JPEG and write its path; the chosen frame always wins over the auto default.
 */
class ThumbnailRepository(
    private val dao: VideoThumbnailDao,
    private val extractor: FrameExtractor,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ThumbnailController {

    private val inFlight = HashSet<String>()
    private val inFlightLock = Mutex()
    private val gate = Semaphore(permits = 3)

    override fun observeAll(): Flow<List<VideoThumbnailEntity>> = dao.observeAll()

    override fun ensureAutoThumbnail(mediaUri: String, durationMs: Long) {
        scope.launch { ensureAutoThumbnailNow(mediaUri, durationMs) }
    }

    override fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long) {
        scope.launch { setCustomThumbnailFromFrameNow(mediaUri, frameMs) }
    }

    override fun resetToAuto(mediaUri: String) {
        scope.launch { resetToAutoNow(mediaUri) }
    }

    suspend fun ensureAutoThumbnailNow(mediaUri: String, durationMs: Long) {
        val existing = dao.getByUri(mediaUri)
        if (existing?.autoResolved == true || existing?.customThumbnailPath != null) return

        // Dedup concurrent compute for the same uri.
        inFlightLock.withLock {
            if (mediaUri in inFlight) return
            inFlight.add(mediaUri)
        }
        try {
            val frameMs = gate.withPermit {
                val stats = extractor.sampleStats(mediaUri, durationMs)
                if (stats.isEmpty()) {
                    minOf(1000L, durationMs / 2).coerceAtLeast(0L)
                } else {
                    val best = pickBestFrame(stats)
                    (durationMs * THUMBNAIL_SAMPLE_FRACTIONS[best]).toLong()
                }
            }
            val base = dao.getByUri(mediaUri) ?: VideoThumbnailEntity(mediaUri)
            dao.upsert(base.copy(autoFrameMs = frameMs, autoResolved = true, updatedAtEpochMs = nowMs()))
        } finally {
            inFlightLock.withLock { inFlight.remove(mediaUri) }
        }
    }

    suspend fun setCustomThumbnailFromFrameNow(mediaUri: String, frameMs: Long) {
        val savedPath = extractor.extractAndSave(mediaUri, frameMs) ?: return
        val existing = dao.getByUri(mediaUri)
        // Delete the previous custom file (if any) so it can't leak or be served stale.
        existing?.customThumbnailPath?.let { old -> if (old != savedPath) runCatching { File(old).delete() } }
        val base = existing ?: VideoThumbnailEntity(mediaUri)
        dao.upsert(base.copy(customThumbnailPath = savedPath, updatedAtEpochMs = nowMs()))
    }

    suspend fun resetToAutoNow(mediaUri: String) {
        val existing = dao.getByUri(mediaUri) ?: return
        existing.customThumbnailPath?.let { runCatching { File(it).delete() } }
        dao.upsert(existing.copy(customThumbnailPath = null, updatedAtEpochMs = nowMs()))
    }

    companion object {
        @Volatile private var INSTANCE: ThumbnailRepository? = null
        fun getInstance(context: Context): ThumbnailRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThumbnailRepository(
                    dao = AppDatabase.getInstance(context).videoThumbnailDao(),
                    extractor = MediaMetadataFrameExtractor(context.applicationContext),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { INSTANCE = it }
            }
    }
}
