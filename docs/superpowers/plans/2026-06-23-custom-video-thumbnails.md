# Custom & Smart Video Thumbnails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the always-black 1-second thumbnail with (a) a smart auto-default that skips black/blank frames, and (b) a per-video manual override settable from the library (frame picker) or the player (capture current frame), persisted forever.

**Architecture:** A pure, unit-tested scoring function in `:core:model` decides the best frame from luminance/variance stats. A thin `FrameExtractor` (MediaMetadataRetriever) produces those stats and extracts/saves JPEGs. A `ThumbnailRepository` (new Room table `video_thumbnail`, keyed by `mediaUri`) orchestrates compute-once auto-defaults and manual overrides, exposed to the library via a narrow `ThumbnailController` seam mirroring the existing `MemorySource` pattern. Display resolves each video to either a saved JPEG (manual) or a Coil `videoFrameMillis(autoFrameMs)` request (auto).

**Tech Stack:** Kotlin, Jetpack Compose, Coil 3 (`coil-video`), Room 2.6.1, MediaMetadataRetriever, Media3 1.5.1. JUnit4 + Truth + Robolectric (Room) + kotlinx-coroutines-test.

## Global Constraints

- `:core:*` modules MUST stay free of `android.*`, `androidx.compose.*`, and `media3` imports. Android/MMR/Coil live only in `:app`. (CLAUDE.md)
- Builds require JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every Gradle call.
- minSdk 24, targetSdk/compileSdk 35. `getScaledFrameAtTime(Long, Int, Int, Int)` is API 27+; provide a `getFrameAtTime` + manual-scale fallback for 24–26.
- Every Kotlin file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- Tests: app Room/DAO tests use `@RunWith(RobolectricTestRunner::class)` + `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`; pure `:core:model` tests are plain JUnit. Assertions use `com.google.common.truth.Truth.assertThat`.
- Commit after every green step. Branch is already `feat/custom-video-thumbnails`.
- Test command: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`. Single module: `./gradlew :core:model:test` / `./gradlew :app:testDebugUnitTest`.

---

### Task 1: Pure frame-scoring + luminance stats (`:core:model`)

**Files:**
- Create: `core/model/src/main/kotlin/com/videoplayer/core/model/ThumbnailScoring.kt`
- Test: `core/model/src/test/kotlin/com/videoplayer/core/model/ThumbnailScoringTest.kt`

**Interfaces:**
- Produces:
  - `data class FrameStats(val avgLuminance: Double, val variance: Double)` — luma normalized 0..1, variance of normalized luma (0..~0.25).
  - `fun luminanceStats(pixels: IntArray): FrameStats` — pixels are ARGB ints (as from `Bitmap.getPixels`).
  - `fun pickBestFrame(candidates: List<FrameStats>): Int` — index of best; ties resolve to the earliest index; throws `IllegalArgumentException` on empty.
  - `fun frameScore(stats: FrameStats): Double` — public so the picker/diagnostics can reuse it.
  - `val THUMBNAIL_SAMPLE_FRACTIONS: List<Float>` = `[0.15, 0.30, 0.50, 0.70, 0.85]`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailScoringTest {

    @Test fun `pickBestFrame throws on empty`() {
        try {
            pickBestFrame(emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }

    @Test fun `pure black candidates fall back to first index`() {
        val black = FrameStats(avgLuminance = 0.0, variance = 0.0)
        assertThat(pickBestFrame(listOf(black, black, black))).isEqualTo(0)
    }

    @Test fun `textured mid-luminance frame beats black and white`() {
        val black = FrameStats(0.01, 0.0)
        val textured = FrameStats(0.45, 0.06)
        val white = FrameStats(0.99, 0.0)
        assertThat(pickBestFrame(listOf(black, textured, white))).isEqualTo(1)
    }

    @Test fun `among non-extreme frames, more detail wins`() {
        val flat = FrameStats(0.5, 0.001)
        val detailed = FrameStats(0.5, 0.08)
        assertThat(pickBestFrame(listOf(flat, detailed))).isEqualTo(1)
    }

    @Test fun `near-black is penalized below a detailed mid frame`() {
        val nearBlack = FrameStats(0.05, 0.02)
        val mid = FrameStats(0.4, 0.03)
        assertThat(pickBestFrame(listOf(nearBlack, mid))).isEqualTo(1)
    }

    @Test fun `luminanceStats computes mean and variance of a black-and-white split`() {
        // 2 black + 2 white pixels: mean luma = 0.5, variance = 0.25
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val stats = luminanceStats(intArrayOf(black, black, white, white))
        assertThat(stats.avgLuminance).isWithin(1e-6).of(0.5)
        assertThat(stats.variance).isWithin(1e-6).of(0.25)
    }

    @Test fun `luminanceStats of all-black is zero`() {
        val stats = luminanceStats(IntArray(16) { 0xFF000000.toInt() })
        assertThat(stats.avgLuminance).isWithin(1e-6).of(0.0)
        assertThat(stats.variance).isWithin(1e-6).of(0.0)
    }

    @Test fun `empty pixels yields zero stats`() {
        assertThat(luminanceStats(IntArray(0))).isEqualTo(FrameStats(0.0, 0.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:model:test --tests "com.videoplayer.core.model.ThumbnailScoringTest"`
Expected: FAIL — `Unresolved reference: pickBestFrame` / `luminanceStats` / `FrameStats`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

/** Sample points (fractions of duration) used to choose a non-black auto-default frame. */
val THUMBNAIL_SAMPLE_FRACTIONS: List<Float> = listOf(0.15f, 0.30f, 0.50f, 0.70f, 0.85f)

/** Per-frame brightness/detail summary. [avgLuminance] and [variance] are over luma in 0..1. */
data class FrameStats(val avgLuminance: Double, val variance: Double)

/**
 * Mean and variance of perceptual luma (Rec. 601) for a block of ARGB pixels (as produced by
 * `Bitmap.getPixels`). Pure integer/double math so it is JVM-unit-testable with no Android types.
 */
fun luminanceStats(pixels: IntArray): FrameStats {
    if (pixels.isEmpty()) return FrameStats(0.0, 0.0)
    var sum = 0.0
    var sumSq = 0.0
    for (p in pixels) {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        sum += luma
        sumSq += luma * luma
    }
    val n = pixels.size
    val mean = sum / n
    val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
    return FrameStats(mean, variance)
}

/**
 * Heuristic desirability of a candidate thumbnail frame: rewards detail (variance), penalizes
 * near-black, blown-out, and flat/uniform frames. Higher is better. Tuned conservatively so a
 * legitimately dark-but-detailed frame still beats a pure-black one (manual override always wins).
 */
fun frameScore(stats: FrameStats): Double {
    val luma = stats.avgLuminance
    val detail = stats.variance
    val brightnessPenalty = when {
        luma < 0.06 -> 1.0   // essentially black
        luma < 0.12 -> 0.5   // very dark
        luma > 0.96 -> 0.6   // blown out
        else -> 0.0
    }
    val flatnessPenalty = if (detail < 0.002) 0.4 else 0.0
    return detail * 3.0 - brightnessPenalty - flatnessPenalty
}

/** Index of the highest-scoring frame; ties resolve to the earliest index. */
fun pickBestFrame(candidates: List<FrameStats>): Int {
    require(candidates.isNotEmpty()) { "candidates must not be empty" }
    var bestIdx = 0
    var best = Double.NEGATIVE_INFINITY
    candidates.forEachIndexed { i, s ->
        val score = frameScore(s)
        if (score > best) { best = score; bestIdx = i }
    }
    return bestIdx
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:model:test --tests "com.videoplayer.core.model.ThumbnailScoringTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/videoplayer/core/model/ThumbnailScoring.kt core/model/src/test/kotlin/com/videoplayer/core/model/ThumbnailScoringTest.kt
git commit -m "feat(thumbnails): pure frame-scoring + luminance stats in :core:model"
```

---

### Task 2: Room entity, DAO, and migration v2→v3 (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/VideoThumbnailEntity.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/VideoThumbnailDao.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/AppDatabase.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/memory/VideoThumbnailDaoTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `VideoThumbnailEntity(mediaUri: String, customThumbnailPath: String? = null, autoFrameMs: Long? = null, autoResolved: Boolean = false, updatedAtEpochMs: Long = 0L)`
  - `VideoThumbnailDao`: `suspend fun upsert(entity)`, `suspend fun getByUri(uri): VideoThumbnailEntity?`, `fun observeAll(): Flow<List<VideoThumbnailEntity>>`
  - `AppDatabase.videoThumbnailDao(): VideoThumbnailDao`; DB version `3`; `MIGRATION_2_3`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoThumbnailDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoThumbnailDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoThumbnailDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `missing uri returns null`() = runTest {
        assertThat(dao.getByUri("nope")).isNull()
    }

    @Test fun `upsert then read round-trips all fields`() = runTest {
        val e = VideoThumbnailEntity("content://v/1", "/files/thumbnails/a.jpg", 4200L, true, 99L)
        dao.upsert(e)
        assertThat(dao.getByUri("content://v/1")).isEqualTo(e)
    }

    @Test fun `defaults persist for an auto-only row`() = runTest {
        dao.upsert(VideoThumbnailEntity(mediaUri = "u", autoFrameMs = 1000L, autoResolved = true))
        val row = dao.getByUri("u")!!
        assertThat(row.customThumbnailPath).isNull()
        assertThat(row.autoResolved).isTrue()
    }

    @Test fun `upsert replaces an existing row`() = runTest {
        dao.upsert(VideoThumbnailEntity("u", autoFrameMs = 1L, autoResolved = true, updatedAtEpochMs = 1L))
        dao.upsert(VideoThumbnailEntity("u", customThumbnailPath = "/p.jpg", updatedAtEpochMs = 2L))
        val row = dao.getByUri("u")!!
        assertThat(row.customThumbnailPath).isEqualTo("/p.jpg")
        assertThat(row.updatedAtEpochMs).isEqualTo(2L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.VideoThumbnailDaoTest"`
Expected: FAIL — `Unresolved reference: videoThumbnailDao` / `VideoThumbnailEntity`.

- [ ] **Step 3: Write minimal implementation**

`VideoThumbnailEntity.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-video thumbnail state, keyed by the same media content/URI string as [PlaybackMemoryEntity].
 *
 * Two layers: [autoFrameMs] is the smart-chosen default timestamp (computed once, then cached);
 * [customThumbnailPath] is a user-set override saved as a JPEG in internal storage and always wins.
 * [autoResolved] is set true once auto compute has run (success OR fallback) so it never re-runs.
 */
@Entity(tableName = "video_thumbnail")
data class VideoThumbnailEntity(
    @PrimaryKey val mediaUri: String,
    val customThumbnailPath: String? = null,
    val autoFrameMs: Long? = null,
    val autoResolved: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
)
```

`VideoThumbnailDao.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoThumbnailDao {
    @Upsert
    suspend fun upsert(entity: VideoThumbnailEntity)

    @Query("SELECT * FROM video_thumbnail WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): VideoThumbnailEntity?

    @Query("SELECT * FROM video_thumbnail")
    fun observeAll(): Flow<List<VideoThumbnailEntity>>
}
```

Modify `AppDatabase.kt` — update the `@Database` annotation, add the DAO accessor, add the migration, and register it. Replace the annotation line and the `playbackMemoryDao()` block, and the `MIGRATION_1_2`/`getInstance` blocks:

```kotlin
@Database(
    entities = [PlaybackMemoryEntity::class, VideoThumbnailEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackMemoryDao(): PlaybackMemoryDao
    abstract fun videoThumbnailDao(): VideoThumbnailDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playback_memory ADD COLUMN subtitleRate REAL NOT NULL DEFAULT 1.0",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_thumbnail` (" +
                        "`mediaUri` TEXT NOT NULL, " +
                        "`customThumbnailPath` TEXT, " +
                        "`autoFrameMs` INTEGER, " +
                        "`autoResolved` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`mediaUri`))",
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_player.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
```

Note: the migration column types MUST mirror what Room generates for the entity (`TEXT` nullable, `INTEGER` nullable for `Long?`, `INTEGER NOT NULL DEFAULT 0` for the non-null `Boolean`/`Long`). Room validates the schema on open; the emulator smoke in Task 9 (upgrading over an installed v2 DB) exercises the migration for real.

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.VideoThumbnailDaoTest"`
Expected: PASS (4 tests). Also run `--tests "com.videoplayer.app.data.memory.PlaybackMemoryDaoTest"` to confirm the existing DAO still builds against the v3 DB.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/memory/VideoThumbnailEntity.kt app/src/main/kotlin/com/videoplayer/app/data/memory/VideoThumbnailDao.kt app/src/main/kotlin/com/videoplayer/app/data/memory/AppDatabase.kt app/src/test/kotlin/com/videoplayer/app/data/memory/VideoThumbnailDaoTest.kt
git commit -m "feat(thumbnails): video_thumbnail Room table + DAO + migration v2->v3"
```

---

### Task 3: FrameExtractor seam + MediaMetadataRetriever impl + ThumbnailSpec (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/thumbnail/FrameExtractor.kt` (interface + filename helper)
- Create: `app/src/main/kotlin/com/videoplayer/app/thumbnail/MediaMetadataFrameExtractor.kt` (Android impl)
- Create: `app/src/main/kotlin/com/videoplayer/app/thumbnail/ThumbnailSpec.kt` (UI-facing resolved type)
- Test: `app/src/test/kotlin/com/videoplayer/app/thumbnail/ThumbnailFileNameTest.kt`

**Interfaces:**
- Consumes: `FrameStats`, `luminanceStats` (Task 1).
- Produces:
  - `interface FrameExtractor { suspend fun sampleStats(mediaUri: String, durationMs: Long): List<FrameStats>; suspend fun extractAndSave(mediaUri: String, frameMs: Long): String? }`
  - `fun thumbnailFileName(mediaUri: String, updatedAtEpochMs: Long): String` — stable, collision-resistant filename (`"<hash>-<updatedAt>.jpg"`).
  - `sealed interface ThumbnailSpec { data class Custom(val path: String, val updatedAtEpochMs: Long); data class AutoFrame(val frameMs: Long) }` plus `fun VideoThumbnailEntity.toSpec(): ThumbnailSpec?`.

- [ ] **Step 1: Write the failing test** (the only purely-testable piece here — the filename helper)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailFileNameTest {

    @Test fun `same uri and time produce a stable name`() {
        val a = thumbnailFileName("content://media/external/video/media/12", 555L)
        val b = thumbnailFileName("content://media/external/video/media/12", 555L)
        assertThat(a).isEqualTo(b)
        assertThat(a).endsWith("-555.jpg")
    }

    @Test fun `different uris produce different names`() {
        val a = thumbnailFileName("content://v/1", 1L)
        val b = thumbnailFileName("content://v/2", 1L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `re-set at a new time changes the name so Coil cache busts`() {
        val a = thumbnailFileName("content://v/1", 1L)
        val b = thumbnailFileName("content://v/1", 2L)
        assertThat(a).isNotEqualTo(b)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.thumbnail.ThumbnailFileNameTest"`
Expected: FAIL — `Unresolved reference: thumbnailFileName`.

- [ ] **Step 3: Write minimal implementation**

`FrameExtractor.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.videoplayer.core.model.FrameStats

/**
 * Decodes still frames from a video. Implemented with MediaMetadataRetriever in :app; abstracted as
 * an interface so [com.videoplayer.app.thumbnail.ThumbnailRepository] can be unit-tested with a fake.
 */
interface FrameExtractor {
    /** Tiny-bitmap luminance/variance samples at [com.videoplayer.core.model.THUMBNAIL_SAMPLE_FRACTIONS]. */
    suspend fun sampleStats(mediaUri: String, durationMs: Long): List<FrameStats>

    /** Extracts the frame at [frameMs], saves it as a JPEG in internal storage, returns its path (or null). */
    suspend fun extractAndSave(mediaUri: String, frameMs: Long): String?
}

/** Stable per-(uri,time) JPEG filename. The time component busts Coil's file-path cache on re-set. */
fun thumbnailFileName(mediaUri: String, updatedAtEpochMs: Long): String {
    val hash = mediaUri.hashCode().toLong() and 0xFFFFFFFFL
    return "${hash.toString(16)}-$updatedAtEpochMs.jpg"
}
```

`ThumbnailSpec.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.videoplayer.app.data.memory.VideoThumbnailEntity

/** What the UI should actually render for a video. Absence (null) means "not resolved yet". */
sealed interface ThumbnailSpec {
    /** A user-set override saved to disk; [updatedAtEpochMs] feeds the Coil cache key. */
    data class Custom(val path: String, val updatedAtEpochMs: Long) : ThumbnailSpec
    /** The smart-chosen default timestamp; Coil decodes it via videoFrameMillis. */
    data class AutoFrame(val frameMs: Long) : ThumbnailSpec
}

/** Resolution precedence: manual override → resolved auto frame → null (recompute/placeholder). */
fun VideoThumbnailEntity.toSpec(): ThumbnailSpec? = when {
    customThumbnailPath != null -> ThumbnailSpec.Custom(customThumbnailPath, updatedAtEpochMs)
    autoResolved && autoFrameMs != null -> ThumbnailSpec.AutoFrame(autoFrameMs)
    else -> null
}
```

`MediaMetadataFrameExtractor.kt`:
```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.thumbnail.ThumbnailFileNameTest"`
Expected: PASS (3 tests). (The MMR impl is exercised by the emulator smoke in Task 9, not unit tests.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/thumbnail/ app/src/test/kotlin/com/videoplayer/app/thumbnail/ThumbnailFileNameTest.kt
git commit -m "feat(thumbnails): FrameExtractor seam, MMR impl, ThumbnailSpec resolver"
```

---

### Task 4: ThumbnailController seam + ThumbnailRepository (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/thumbnail/ThumbnailRepository.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/thumbnail/ThumbnailRepositoryTest.kt`

**Interfaces:**
- Consumes: `FrameExtractor`, `thumbnailFileName` (Task 3); `pickBestFrame`, `THUMBNAIL_SAMPLE_FRACTIONS` (Task 1); `VideoThumbnailDao`, `VideoThumbnailEntity` (Task 2).
- Produces:
  - `interface ThumbnailController { fun observeAll(): Flow<List<VideoThumbnailEntity>>; fun ensureAutoThumbnail(mediaUri: String, durationMs: Long); fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long); fun resetToAuto(mediaUri: String) }` — all non-Android types (keeps `LibraryViewModel` JVM-testable).
  - `class ThumbnailRepository(...) : ThumbnailController` with constructor `(dao: VideoThumbnailDao, extractor: FrameExtractor, scope: CoroutineScope, nowMs: () -> Long = { System.currentTimeMillis() })` and `companion object { fun getInstance(context: Context): ThumbnailRepository }`.
  - Suspend cores (called by the fire-and-forget interface methods, and directly by tests): `suspend fun ensureAutoThumbnailNow(mediaUri, durationMs)`, `suspend fun setCustomThumbnailFromFrameNow(mediaUri, frameMs)`, `suspend fun resetToAutoNow(mediaUri)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.VideoThumbnailDao
import com.videoplayer.core.model.FrameStats
import kotlinx.coroutines.test.StandardTestDispatcher
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
        assertThat(dao.getByUri("u")!!.autoFrameMs).isEqualTo(3000L)
    }

    @Test fun `resetToAuto clears the custom path`() = runTest {
        val ext = FakeExtractor(emptyList(), savePath = "/files/thumbnails/x.jpg")
        val r = repo(ext, this)
        r.setCustomThumbnailFromFrameNow("u", 4200L)
        r.resetToAutoNow("u")
        assertThat(dao.getByUri("u")!!.customThumbnailPath).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.thumbnail.ThumbnailRepositoryTest"`
Expected: FAIL — `Unresolved reference: ThumbnailRepository`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.thumbnail.ThumbnailRepositoryTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/thumbnail/ThumbnailRepository.kt app/src/test/kotlin/com/videoplayer/app/thumbnail/ThumbnailRepositoryTest.kt
git commit -m "feat(thumbnails): ThumbnailRepository with lazy auto-default + manual override"
```

---

### Task 5: Wire thumbnails into LibraryViewModel + app construction

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt:54-67`
- Create: `app/src/test/kotlin/com/videoplayer/app/library/FakeThumbnailController.kt`
- Modify: existing library VM tests that construct `LibraryViewModel` (see Step 1) to pass the fake.

**Interfaces:**
- Consumes: `ThumbnailController`, `ThumbnailRepository`, `ThumbnailSpec`, `VideoThumbnailEntity.toSpec()` (Tasks 3–4).
- Produces:
  - `LibraryUiState.thumbnailByUri: Map<String, ThumbnailSpec>`
  - `LibraryViewModel(sourceManager, memorySource, thumbnails: ThumbnailController, settings)` constructor (new 3rd param)
  - `LibraryViewModel.ensureThumbnail(mediaUri, durationMs)`, `setCustomThumbnailFromFrame(mediaUri, frameMs)`, `resetThumbnail(mediaUri)`.

- [ ] **Step 1: Write the failing test**

Create `FakeThumbnailController.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.memory.VideoThumbnailEntity
import com.videoplayer.app.thumbnail.ThumbnailController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThumbnailController(
    private val rows: MutableStateFlow<List<VideoThumbnailEntity>> = MutableStateFlow(emptyList()),
) : ThumbnailController {
    val ensured = mutableListOf<Pair<String, Long>>()
    val customSet = mutableListOf<Pair<String, Long>>()
    val reset = mutableListOf<String>()
    fun emit(list: List<VideoThumbnailEntity>) { rows.value = list }
    override fun observeAll(): Flow<List<VideoThumbnailEntity>> = rows
    override fun ensureAutoThumbnail(mediaUri: String, durationMs: Long) { ensured += mediaUri to durationMs }
    override fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long) { customSet += mediaUri to frameMs }
    override fun resetToAuto(mediaUri: String) { reset += mediaUri }
}
```

Add a test to a new file `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelThumbnailTest.kt`. Follow the construction pattern used by the existing `LibraryViewModelTest` (read it first — it wires `FakeSourceManager`, `FakeMemorySource`, `FakeGridSizePreferences`, and uses `MainDispatcherRule`). Mirror that setup and add the new `FakeThumbnailController` argument:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.data.memory.VideoThumbnailEntity
import com.videoplayer.app.thumbnail.ThumbnailSpec
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LibraryViewModelThumbnailTest {

    @get:Rule val mainRule = MainDispatcherRule()

    // NOTE: copy the exact builder the existing LibraryViewModelTest uses; only the thumbnails arg is new.
    private fun viewModel(thumbs: FakeThumbnailController) = LibraryViewModel(
        sourceManager = FakeSourceManager(/* seed videos as the existing test does */),
        memorySource = FakeMemorySource(),
        thumbnails = thumbs,
        settings = FakeGridSizePreferences(),
    )

    @Test fun `thumbnailByUri maps a resolved auto row to AutoFrame`() = runTest {
        val thumbs = FakeThumbnailController()
        val vm = viewModel(thumbs)
        thumbs.emit(listOf(VideoThumbnailEntity("u", autoFrameMs = 2500L, autoResolved = true)))
        val state = vm.uiState.first { it.thumbnailByUri.containsKey("u") }
        assertThat(state.thumbnailByUri["u"]).isEqualTo(ThumbnailSpec.AutoFrame(2500L))
    }

    @Test fun `custom row wins over auto`() = runTest {
        val thumbs = FakeThumbnailController()
        val vm = viewModel(thumbs)
        thumbs.emit(listOf(VideoThumbnailEntity("u", "/p.jpg", 2500L, true, 9L)))
        val state = vm.uiState.first { it.thumbnailByUri["u"] is ThumbnailSpec.Custom }
        assertThat(state.thumbnailByUri["u"]).isEqualTo(ThumbnailSpec.Custom("/p.jpg", 9L))
    }

    @Test fun `ensureThumbnail forwards to the controller`() = runTest {
        val thumbs = FakeThumbnailController()
        viewModel(thumbs).ensureThumbnail("u", 12_000L)
        assertThat(thumbs.ensured).contains("u" to 12_000L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.LibraryViewModelThumbnailTest"`
Expected: FAIL — constructor arity mismatch / `thumbnailByUri` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `LibraryViewModel.kt`:

1. Add imports: `import com.videoplayer.app.thumbnail.ThumbnailController`, `import com.videoplayer.app.thumbnail.ThumbnailSpec`, `import com.videoplayer.app.thumbnail.toSpec`.
2. Add field to `LibraryUiState` (after `progressByUri`):
   ```kotlin
   val thumbnailByUri: Map<String, ThumbnailSpec> = emptyMap(),
   ```
3. Add constructor param (3rd position, before `settings`):
   ```kotlin
   class LibraryViewModel(
       private val sourceManager: LibrarySourceManager,
       memorySource: MemorySource,
       private val thumbnails: ThumbnailController,
       private val settings: GridSizePreferences,
   ) : ViewModel() {
   ```
4. Extend the `uiState` combine to a 5th source. Replace the `combine(sources, memorySource.observeAll(), controls, loading)` call with the 5-arg overload that also takes `thumbnails.observeAll()`, compute the map, and pass it into `LibraryUiState`:
   ```kotlin
   val uiState: StateFlow<LibraryUiState> =
       combine(
           sources, memorySource.observeAll(), thumbnails.observeAll(), controls, loading,
       ) { src, memory, thumbs, c, isLoading ->
           // ... existing body unchanged ...
           val thumbnailByUri = thumbs.mapNotNull { t -> t.toSpec()?.let { t.mediaUri to it } }.toMap()
           LibraryUiState(
               // ... existing args ...
               progressByUri = progressByUri,
               thumbnailByUri = thumbnailByUri,
               savedFolders = src.saved, activeSource = src.active, isLoading = isLoading,
           )
       }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())
   ```
   (The 5-typed-arg `combine` overload exists in kotlinx-coroutines. `isLoading` moves to the 5th lambda param.)
5. Add the three forwarding methods next to `setTab` etc.:
   ```kotlin
   fun ensureThumbnail(mediaUri: String, durationMs: Long) = thumbnails.ensureAutoThumbnail(mediaUri, durationMs)
   fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long) = thumbnails.setCustomThumbnailFromFrame(mediaUri, frameMs)
   fun resetThumbnail(mediaUri: String) = thumbnails.resetToAuto(mediaUri)
   ```

In `VideoPlayerApp.kt` (the `viewModel { }` factory, lines 54–67): construct the repo and pass it. Replace the factory body:
```kotlin
val libraryViewModel: LibraryViewModel = viewModel {
    val db = AppDatabase.getInstance(appContext)
    val settingsRepository = SettingsRepository(appContext.settingsDataStore)
    val manager = LibrarySourceManager(
        store = LibrarySourceStore(appContext.libraryDataStore),
        globalRepository = MediaStoreRepository(appContext),
        folderRepositoryFactory = { treeUri -> SafFolderRepository(appContext, Uri.parse(treeUri)) },
    )
    LibraryViewModel(
        manager,
        PlaybackMemoryRepository(db.playbackMemoryDao(), settingsRepository),
        ThumbnailRepository.getInstance(appContext),
        settingsRepository,
    )
}
```
Add import `import com.videoplayer.app.thumbnail.ThumbnailRepository`.

Then update the existing `LibraryViewModelTest`, `LibraryViewModelStateTest`, `LibraryViewModelSourceTest` constructions to pass `FakeThumbnailController()` as the new 3rd argument (read each and insert the arg).

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.*"`
Expected: PASS — the new thumbnail test plus all pre-existing library VM tests (now passing the fake).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt app/src/test/kotlin/com/videoplayer/app/library/
git commit -m "feat(thumbnails): expose thumbnailByUri + ensure/set/reset on LibraryViewModel"
```

---

### Task 6: Resolve specs in the library display (`VideoThumbnail` / `ThumbnailTile` / `MediaRow`)

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (`VideoThumbnail` 519–539, `ThumbnailTile` 396–467, `MediaRow` 469–516, and their call sites)

**Interfaces:**
- Consumes: `LibraryUiState.thumbnailByUri`, `ThumbnailSpec`, `LibraryViewModel.ensureThumbnail` (Task 5).
- Produces: tiles that render the resolved thumbnail and trigger lazy auto-compute for on-screen videos; `ThumbnailTile`/`MediaRow` gain `spec`, `onEnsure`, `onLongPress` params (the last is consumed in Task 7).

This task has no unit test (Compose UI is not unit-tested in this repo). Verification = compile + the Task 9 emulator smoke. Keep the change a faithful refactor of the existing composables.

- [ ] **Step 1: Update `VideoThumbnail` to resolve a spec**

Replace the `VideoThumbnail` composable (lines 519–539) with a version that takes the resolved spec and an ensure callback. Imports to add at the top of the file: `import com.videoplayer.app.thumbnail.ThumbnailSpec`, `import java.io.File`, `import androidx.compose.runtime.LaunchedEffect`, `import coil3.request.videoFramePercent` is NOT needed; keep `videoFrameMillis`.

```kotlin
@Composable
private fun VideoThumbnail(
    uri: String,
    durationMs: Long,
    spec: ThumbnailSpec?,
    onEnsure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Lazily compute the smart auto-default the first time an un-resolved tile appears.
    LaunchedEffect(uri, spec == null) {
        if (spec == null) onEnsure()
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.OndemandVideo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp),
        )
        val request = when (spec) {
            is ThumbnailSpec.Custom -> ImageRequest.Builder(context)
                .data(File(spec.path))
                .memoryCacheKey("thumb:${spec.path}:${spec.updatedAtEpochMs}")
                .diskCacheKey("thumb:${spec.path}:${spec.updatedAtEpochMs}")
                .crossfade(true)
                .build()
            is ThumbnailSpec.AutoFrame -> ImageRequest.Builder(context)
                .data(uri)
                .videoFrameMillis(spec.frameMs)
                .crossfade(true)
                .build()
            null -> null
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

- [ ] **Step 2: Thread the new params through `ThumbnailTile` and `MediaRow`**

`ThumbnailTile` (line 397): add params and wrap click with long-press; update the `VideoThumbnail` call (line 414):
```kotlin
@Composable
internal fun ThumbnailTile(
    item: MediaItem,
    progress: Float,
    spec: ThumbnailSpec?,
    onEnsure: (MediaItem) -> Unit,
    onClick: () -> Unit,
    onLongPress: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.combinedClickable(onClick = onClick, onLongClick = { onLongPress(item) }),
    ) {
        Box(/* ...unchanged... */) {
            VideoThumbnail(
                uri = item.uri,
                durationMs = item.durationMs,
                spec = spec,
                onEnsure = { onEnsure(item) },
                modifier = Modifier.fillMaxSize(),
            )
            // ...rest unchanged...
        }
        // ...title unchanged...
    }
}
```
Add imports: `import androidx.compose.foundation.combinedClickable`, and annotate the file/usages with `@OptIn(ExperimentalFoundationApi::class)` (`import androidx.compose.foundation.ExperimentalFoundationApi`) — `combinedClickable` is experimental in Foundation. Remove the now-unused plain `clickable` import only if nothing else uses it (it is still used by `MediaRow` unless you change it too).

`MediaRow` (line 469): same treatment — add `spec`, `onEnsure`, `onLongPress`, switch `.clickable` to `.combinedClickable`, and update its `VideoThumbnail` call (line 491).

- [ ] **Step 3: Update the call sites**

Find every `ThumbnailTile(...)` and `MediaRow(...)` call (grid `VideosContent`, list view, folder content, and the "Continue watching" `LazyRow`). For each, pass:
```kotlin
spec = uiState.thumbnailByUri[item.uri],
onEnsure = { viewModel.ensureThumbnail(it.uri, it.durationMs) },
onLongPress = { pickerTarget = it },   // pickerTarget is added in Task 7; until then use {} to compile
```
For Task 6 in isolation, wire `onLongPress = {}` so the screen compiles; Task 7 replaces it with the picker host. (Read the call sites — they currently pass `item`, `progress`, `onClick`; add the three new args.) The `uiState` is already collected in `LibraryScreen`; `viewModel` is in scope.

- [ ] **Step 4: Build to verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt
git commit -m "feat(thumbnails): render resolved thumbnail spec + lazy auto-compute in library"
```

---

### Task 7: Frame picker bottom sheet + library long-press entry

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/library/ThumbnailPickerSheet.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (host the sheet + `pickerTarget` state, set `onLongPress`)

**Interfaces:**
- Consumes: `LibraryViewModel.setCustomThumbnailFromFrame`, `resetThumbnail` (Task 5); `LibraryUiState.thumbnailByUri` to know if a custom one exists.
- Produces: `ThumbnailPickerSheet(item, hasCustom, onConfirm: (frameMs: Long) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit)`.

No unit test (UI). Verify by compile + Task 9 smoke. Follow the existing `ModalBottomSheet` pattern from `SubtitleSearchSheet.kt` and the `Slider` pattern from `PlayerControls.kt`.

- [ ] **Step 1: Create `ThumbnailPickerSheet.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.formatDuration

private const val CANDIDATE_COUNT = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailPickerSheet(
    item: MediaItem,
    hasCustom: Boolean,
    onConfirm: (frameMs: Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val duration = item.durationMs.coerceAtLeast(1L)
    // Candidate timestamps evenly spaced across the middle 80% of the video.
    val candidates = remember(duration) {
        (1..CANDIDATE_COUNT).map { i -> duration * i / (CANDIDATE_COUNT + 1) }
    }
    var selectedMs by remember { mutableLongStateOf(candidates[CANDIDATE_COUNT / 2]) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose thumbnail", style = MaterialTheme.typography.titleMedium)

            // Large live preview of the currently selected frame.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.uri).videoFrameMillis(selectedMs).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
            )
            Text(formatDuration(selectedMs), style = MaterialTheme.typography.bodySmall)

            // Fine scrub across the whole video.
            Slider(
                value = selectedMs.toFloat(),
                onValueChange = { selectedMs = it.toLong() },
                valueRange = 0f..duration.toFloat(),
            )

            // Sampled candidate strip.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates) { ms ->
                    val selected = ms == selectedMs
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.uri).videoFrameMillis(ms).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier
                            .width(120.dp).aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { selectedMs = ms },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasCustom) {
                    TextButton(onClick = { onReset(); onDismiss() }) { Text("Reset to automatic") }
                }
                Box(Modifier.weight(1f))
                Button(onClick = { onConfirm(selectedMs); onDismiss() }) { Text("Set thumbnail") }
            }
        }
    }
}
```
Add the missing imports the compiler flags (`androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.width`, `androidx.compose.foundation.layout.width` is `androidx.compose.foundation.layout.width`; `weight` is on `RowScope`). Resolve any import nits during Step 3 build.

- [ ] **Step 2: Host the sheet in `LibraryScreen`**

In the `LibraryScreen` composable body, add state and the host. Near the other `remember` state at the top of `LibraryScreen`:
```kotlin
var pickerTarget by remember { mutableStateOf<MediaItem?>(null) }
```
Just before the closing brace of `LibraryScreen` (after the main content), add:
```kotlin
pickerTarget?.let { target ->
    ThumbnailPickerSheet(
        item = target,
        hasCustom = uiState.thumbnailByUri[target.uri] is com.videoplayer.app.thumbnail.ThumbnailSpec.Custom,
        onConfirm = { ms -> viewModel.setCustomThumbnailFromFrame(target.uri, ms) },
        onReset = { viewModel.resetThumbnail(target.uri) },
        onDismiss = { pickerTarget = null },
    )
}
```
Add imports: `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.setValue`, `import androidx.compose.runtime.getValue`, `import com.videoplayer.core.model.MediaItem` (likely already present).

- [ ] **Step 3: Point `onLongPress` at the picker**

Replace the `onLongPress = {}` placeholders from Task 6 with `onLongPress = { pickerTarget = it }` at every `ThumbnailTile`/`MediaRow` call site.

- [ ] **Step 4: Build to verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (fix any import nits surfaced).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/ThumbnailPickerSheet.kt app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt
git commit -m "feat(thumbnails): frame picker bottom sheet + library long-press entry"
```

---

### Task 8: Player "Set as thumbnail" capture

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt` (add an action in the existing overflow/secondary control row)
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (wire the action to `ThumbnailRepository`)

**Interfaces:**
- Consumes: `ThumbnailRepository.getInstance(context).setCustomThumbnailFromFrame(uri, frameMs)` (Task 4); current media URI + `state.positionMs` from `PlayerScreen`.
- Produces: `PlayerControls` gains an `onSetThumbnail: () -> Unit` parameter, invoked from an overflow `DropdownMenuItem` (or a labelled action consistent with the existing controls).

No unit test (UI). Verify by compile + Task 9 smoke.

- [ ] **Step 1: Read the two files first**

Read `PlayerControls.kt` to find the existing overflow `DropdownMenu` / secondary control row (the speed/subtitle/sleep menus are the pattern), and `PlayerScreen.kt` to find (a) how the currently-playing media URI is known (it tracks `startUri` and a current item; use the URI of the item currently playing) and (b) where `state.positionMs` is read and where `PlayerControls(...)` is invoked.

- [ ] **Step 2: Add the parameter + menu item in `PlayerControls.kt`**

Add `onSetThumbnail: () -> Unit,` to the `PlayerControls` parameter list (near the other `on...` callbacks). In the most appropriate existing menu (e.g. an overflow `DropdownMenu`, mirroring the speed menu at lines ~170–199), add:
```kotlin
DropdownMenuItem(
    text = { Text("Set as thumbnail") },
    onClick = { onSetThumbnail(); /* close the menu via its expanded state */ },
)
```
If there is no general overflow menu, add a small icon button to the top control row using an existing icon (e.g. `Icons.Filled.Image` / `Icons.Filled.PhotoCamera`) with `onClick = onSetThumbnail` and a `contentDescription = "Set as thumbnail"`, matching the styling of the neighbouring control buttons.

- [ ] **Step 3: Wire it in `PlayerScreen.kt`**

Where `PlayerControls(...)` is invoked, pass:
```kotlin
onSetThumbnail = {
    val uri = /* current item uri in scope */ 
    com.videoplayer.app.thumbnail.ThumbnailRepository.getInstance(context)
        .setCustomThumbnailFromFrame(uri, state.positionMs)
    // brief confirmation using whatever snackbar/Toast pattern PlayerScreen already uses
},
```
Use the `LocalContext`/`context` already available in `PlayerScreen`. For the confirmation, reuse the existing feedback mechanism in `PlayerScreen` if one exists; otherwise a short `Toast.makeText(context, "Thumbnail set", Toast.LENGTH_SHORT).show()` is acceptable (import `android.widget.Toast`).

- [ ] **Step 4: Build to verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(thumbnails): capture current frame as thumbnail from the player"
```

---

### Task 9: Full verification, emulator smoke, finish branch

**Files:** none (verification only).

- [ ] **Step 1: Full unit test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`
Expected: BUILD SUCCESSFUL; all modules green (new ThumbnailScoringTest, VideoThumbnailDaoTest, ThumbnailFileNameTest, ThumbnailRepositoryTest, LibraryViewModelThumbnailTest + all pre-existing tests).

- [ ] **Step 2: Assemble debug APK**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Emulator smoke (the real acceptance test)**

Boot AVD `kuran_test`, `./gradlew :app:installDebug` **over the previously installed build** (to exercise the v2→v3 migration on real data), then verify:
1. Library thumbnails are no longer black for videos with black intros (auto-default skipped the black frames).
2. Long-press a video → picker opens; sampled frames + scrub work; "Set thumbnail" updates the tile immediately; "Reset to automatic" reverts it.
3. Play a video, use "Set as thumbnail"; return to library and confirm the tile shows that frame.
4. Kill and relaunch the app → custom + auto thumbnails persist.
Capture screencaps for the summary. If the migration throws on launch, the column types in `MIGRATION_2_3` don't match the entity — fix and reinstall.

- [ ] **Step 4: Finish the branch**

Invoke `superpowers:finishing-a-development-branch`. Update `MASTER_PROMPT.md`/changelog only if the repo convention requires it; otherwise present merge/PR options to the user.

---

## Self-Review

**Spec coverage:** smart auto-default → Tasks 1,3,4,6; manual library pick (grid + fine scrub) → Tasks 3,4,7; player capture → Tasks 4,8; persistence keyed by mediaUri + migration → Task 2; precedence (custom > auto) → Task 3 (`toSpec`) + Task 5 (map); reset → Tasks 4,7; concurrency/lazy compute → Tasks 4,6; pure unit-tested scoring → Task 1; error/fallback handling → Tasks 3,4. All spec sections map to a task.

**Placeholder scan:** No TBD/TODO. The only intentionally deferred wiring is `onLongPress = {}` in Task 6, explicitly replaced in Task 7 Step 3 — called out, not a silent gap. Tasks 6–8 carry full code/edit instructions; the few "read the file first" notes are for surgical edits into large existing files (LibraryScreen call sites, PlayerScreen/PlayerControls) where the exact surrounding lines must be read by the implementer — each still specifies the exact params/calls to add.

**Type consistency:** `ThumbnailController` method names (`ensureAutoThumbnail`, `setCustomThumbnailFromFrame`, `resetToAuto`) are identical across Tasks 4, 5, and the fake. VM methods (`ensureThumbnail`, `setCustomThumbnailFromFrame`, `resetThumbnail`) are consistent between Task 5 impl and Task 7 call sites. `ThumbnailSpec.Custom(path, updatedAtEpochMs)` / `AutoFrame(frameMs)` consistent across Tasks 3, 5, 6, 7. `VideoThumbnailEntity` field names consistent across Tasks 2, 3, 4, 5. `thumbnailFileName(mediaUri, updatedAtEpochMs)` consistent in Tasks 3 (impl) and 3 (test). Sample fractions reused from `THUMBNAIL_SAMPLE_FRACTIONS` in Tasks 1, 3, 4.
