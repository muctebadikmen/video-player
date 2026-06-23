// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.VideoThumbnailDao
import com.videoplayer.core.model.FrameStats
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThumbnailRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoThumbnailDao

    /** Returns canned stats so pickBestFrame is deterministic; counts extraction calls. */
    private class FakeExtractor(
        private val stats: List<FrameStats>,
        private val savePath: String? = "/files/thumbnails/saved.jpg",
    ) : FrameExtractor {
        var sampleCalls = 0; var saveCalls = 0
        override suspend fun sampleStats(mediaUri: String, durationMs: Long): List<FrameStats> {
            sampleCalls++; return stats
        }
        override suspend fun extractAndSave(mediaUri: String, frameMs: Long): String? {
            saveCalls++; return savePath
        }
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.videoThumbnailDao()
    }

    @After fun tearDown() { db.close() }

    private fun repo(extractor: FrameExtractor, scope: TestScope) =
        ThumbnailRepository(dao, extractor, scope, nowMs = { 7L })

    @Test fun `ensureAuto picks the best frame's timestamp and marks resolved`() = runTest {
        // index 1 (0.30 of 10_000ms = 3000ms) is the most textured mid frame.
        val stats = listOf(
            FrameStats(0.0, 0.0), FrameStats(0.4, 0.08),
            FrameStats(0.5, 0.01), FrameStats(0.5, 0.0), FrameStats(0.99, 0.0),
        )
        val ext = FakeExtractor(stats)
        repo(ext, this).ensureAutoThumbnailNow("u", durationMs = 10_000L)
        val row = dao.getByUri("u")!!
        assertThat(row.autoResolved).isTrue()
        assertThat(row.autoFrameMs).isEqualTo(3000L)
    }

    @Test fun `ensureAuto is idempotent - already-resolved row is not recomputed`() = runTest {
        val ext = FakeExtractor(listOf(FrameStats(0.4, 0.08)))
        val r = repo(ext, this)
        r.ensureAutoThumbnailNow("u", 10_000L)
        r.ensureAutoThumbnailNow("u", 10_000L)
        assertThat(ext.sampleCalls).isEqualTo(1)
    }

    @Test fun `ensureAuto with no stats stores a fallback and still marks resolved`() = runTest {
        val ext = FakeExtractor(emptyList())
        repo(ext, this).ensureAutoThumbnailNow("u", 8_000L)
        val row = dao.getByUri("u")!!
        assertThat(row.autoResolved).isTrue()
        assertThat(row.autoFrameMs).isEqualTo(1000L) // min(1000, duration/2)
    }

    @Test fun `setCustom saves a file and writes its path`() = runTest {
        val ext = FakeExtractor(emptyList(), savePath = "/files/thumbnails/x.jpg")
        repo(ext, this).setCustomThumbnailFromFrameNow("u", frameMs = 4200L)
        val row = dao.getByUri("u")!!
        assertThat(row.customThumbnailPath).isEqualTo("/files/thumbnails/x.jpg")
        assertThat(row.updatedAtEpochMs).isEqualTo(7L)
        assertThat(ext.saveCalls).isEqualTo(1)
    }

    @Test fun `setCustom preserves a previously resolved autoFrameMs`() = runTest {
        val ext = FakeExtractor(listOf(FrameStats(0.4, 0.08)))
        val r = repo(ext, this)
        r.ensureAutoThumbnailNow("u", 10_000L)
        r.setCustomThumbnailFromFrameNow("u", 4200L)
        // Single-stat list → pickBestFrame picks index 0 → THUMBNAIL_SAMPLE_FRACTIONS[0]=0.15 → 10_000*0.15=1500ms.
        assertThat(dao.getByUri("u")!!.autoFrameMs).isEqualTo(1500L)
    }

    @Test fun `resetToAuto clears the custom path`() = runTest {
        val ext = FakeExtractor(emptyList(), savePath = "/files/thumbnails/x.jpg")
        val r = repo(ext, this)
        r.setCustomThumbnailFromFrameNow("u", 4200L)
        r.resetToAutoNow("u")
        assertThat(dao.getByUri("u")!!.customThumbnailPath).isNull()
    }
}
