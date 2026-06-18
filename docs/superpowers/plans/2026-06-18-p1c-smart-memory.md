# P1.C — Smart Memory / Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist per-file playback state (resume position, aspect mode, speed) in a Room database so re-opening a video silently resumes where it left off, with a forward-compatible schema reserving columns for features that land in later phases (audio/subtitle track, orientation, V2 language-learning).

**Architecture:** Pure resume/precedence math lives in `:core:playback` (JVM-unit-tested, Android-free). Room (entity + DAO + `AppDatabase`) and a DataStore-backed `SettingsRepository` live in `:app/data/memory`, wrapped by a `PlaybackMemoryRepository` that resolves the start settings using the pure precedence rule (per-file → folder → global). A new `PlayerViewModel` owns that repository, loads resolved settings when the player opens, and persists current state on pause/stop/dispose and periodically while playing. `PlayerScreen` applies the resolved settings once the engine reports `READY` (silent auto-resume).

**Tech Stack:** Kotlin 2.0.21 · Room 2.6.1 (via KSP) · AndroidX DataStore Preferences 1.1.1 · Jetpack Compose · Robolectric 4.14 + JUnit4 + Truth + kotlinx-coroutines-test (tests).

## Global Constraints

- **Platform:** Native Android, Kotlin + Jetpack Compose only.
- **Core is UI-agnostic:** `:core:*` MUST NOT depend on `android.*`, `androidx.compose.*`, `androidx.room`, `androidx.datastore`, or `media3`. Room/DataStore/Android types live only in `:app`.
- **License/privacy:** GPLv3, F-Droid-clean deps only. Room + DataStore are AndroidX FOSS — allowed. Zero telemetry/network.
- **Build:** JDK 21 via `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew`. minSdk 24 / targetSdk 35 / compileSdk 35. Single project wrapper (`./gradlew`, Gradle 8.11.1).
- **Resume UX (user-confirmed):** silent auto-resume — no "Resume / Start over" prompt. Threshold: start from 0 if watched `< 5s` or if saved position is within `5s` of the end (finished); otherwise resume exactly.
- **Forward-compat, no speculative logic:** reserved columns are nullable with documented owning phase; no V2 logic is built. Only resume-position + aspect + speed are wired now (the features that exist).
- **Commit after every green step.** Conventional-commit messages.
- **TDD discipline:** pure logic = real red→green→commit (`:core:playback:test`). Room/DAO/repository/VM = Robolectric unit tests. The PlayerScreen wiring = `[Android-verify]` (`assembleDebug` + emulator resume smoke).

---

## File Structure

**Created:**
- `core/playback/src/main/kotlin/com/videoplayer/core/playback/ResumePolicy.kt` — pure `effectiveResumePosition` + generic `resolvePreference`.
- `core/playback/src/test/kotlin/com/videoplayer/core/playback/ResumePolicyTest.kt`
- `core/playback/src/test/kotlin/com/videoplayer/core/playback/PreferencePrecedenceTest.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryEntity.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDao.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/AppDatabase.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/ResolvedStartSettings.kt`
- `app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDaoTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepositoryTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/player/PlayerViewModelTest.kt`

**Modified:**
- `gradle/libs.versions.toml` — add Room, KSP, DataStore versions/libraries/plugin.
- `app/build.gradle.kts` — apply KSP plugin; add Room + DataStore deps; add `testImplementation` Robolectric + test-ext-junit; add `testOptions`.
- `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` — obtain `PlayerViewModel`, apply resolved settings on `READY`, persist on pause/stop/dispose + periodically.

**Deferred (documented, not built here):**
- `FolderDefaultsEntity` / folder-default writes — the `folder` precedence tier stays `null` until a feature can *set* folder defaults (P1.D/P1.E). The pure `resolvePreference` is fully tested for all three tiers now so it is ready when that lands.
- `audioTrackId` / `subtitleTrackId` / `subtitleOffsetMs` (P1.G), `orientation` (P1.E) — reserved nullable columns, populated later with no migration.
- DataStore `SettingsRepository` has no settings UI yet (P1.H); it provides `resumeEnabled`/`defaultSpeed` defaults consumed by the resolver.

---

## Task C1: Pure resume + precedence math (`:core:playback`) [TDD]

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/ResumePolicy.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/ResumePolicyTest.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/PreferencePrecedenceTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `const val MIN_RESUME_MS = 5_000L`
  - `const val END_GUARD_MS = 5_000L`
  - `fun effectiveResumePosition(savedPositionMs: Long, durationMs: Long): Long`
  - `fun <T> resolvePreference(file: T?, folder: T?, global: T): T`

- [ ] **Step 1: Write the failing tests**

`core/playback/src/test/kotlin/com/videoplayer/core/playback/ResumePolicyTest.kt`:
```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumePolicyTest {
    @Test fun `resumes a mid-file position`() {
        assertThat(effectiveResumePosition(30_000, 120_000)).isEqualTo(30_000)
    }
    @Test fun `too-early position resets to zero`() {
        assertThat(effectiveResumePosition(4_999, 120_000)).isEqualTo(0)
    }
    @Test fun `boundary at min resume is honored`() {
        assertThat(effectiveResumePosition(5_000, 120_000)).isEqualTo(5_000)
    }
    @Test fun `near-end position resets to zero (finished)`() {
        assertThat(effectiveResumePosition(118_000, 120_000)).isEqualTo(0)
    }
    @Test fun `unknown duration honors a valid saved position`() {
        assertThat(effectiveResumePosition(30_000, 0)).isEqualTo(30_000)
    }
    @Test fun `negative saved position resets to zero`() {
        assertThat(effectiveResumePosition(-1, 120_000)).isEqualTo(0)
    }
}
```

`core/playback/src/test/kotlin/com/videoplayer/core/playback/PreferencePrecedenceTest.kt`:
```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreferencePrecedenceTest {
    @Test fun `per-file value wins over folder and global`() {
        assertThat(resolvePreference(2f, 3f, 1f)).isEqualTo(2f)
    }
    @Test fun `folder value used when file is null`() {
        assertThat(resolvePreference(null, 3f, 1f)).isEqualTo(3f)
    }
    @Test fun `global value used when file and folder are null`() {
        assertThat(resolvePreference<Float>(null, null, 1f)).isEqualTo(1f)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: FAIL — `effectiveResumePosition` / `resolvePreference` unresolved.

- [ ] **Step 3: Implement `ResumePolicy.kt`**

```kotlin
package com.videoplayer.core.playback

/** Minimum watched position worth resuming. Below this we start from the beginning. */
const val MIN_RESUME_MS = 5_000L

/** If the saved position is within this of the end, treat the file as finished and restart. */
const val END_GUARD_MS = 5_000L

/**
 * The position playback should start from, given a previously saved [savedPositionMs]
 * and the media [durationMs]. Silent auto-resume policy:
 *  - too-early saves (`< MIN_RESUME_MS`, incl. negatives) start from 0,
 *  - near-the-end saves (within [END_GUARD_MS] of a known duration) start from 0 (finished),
 *  - everything else resumes exactly where it left off.
 *
 * A non-positive [durationMs] means duration is unknown (player hasn't reported it yet);
 * we still honor a valid saved position so resume works before duration is known.
 */
fun effectiveResumePosition(savedPositionMs: Long, durationMs: Long): Long {
    if (savedPositionMs < MIN_RESUME_MS) return 0
    if (durationMs > 0 && savedPositionMs >= durationMs - END_GUARD_MS) return 0
    return savedPositionMs
}

/**
 * Three-tier preference precedence: a per-file value wins, then a folder default,
 * then the always-present global value. Folder defaults are not yet persisted in V1
 * (no setter exists); callers pass `null` for [folder] until that lands.
 */
fun <T> resolvePreference(file: T?, folder: T?, global: T): T = file ?: folder ?: global
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: PASS (all ResumePolicy + PreferencePrecedence tests + the pre-existing FakePlaybackEngine tests).

- [ ] **Step 5: Commit**

```bash
git add core/playback
git commit -m "feat(core): pure resume-position policy and preference precedence with tests"
```

---

## Task C2: Room persistence layer (`:app/data/memory`) [TDD + build wiring]

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryEntity.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDao.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/AppDatabase.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDaoTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class PlaybackMemoryEntity(mediaUri: String, positionMs: Long, durationMs: Long, aspectMode: String, speed: Float, updatedAtEpochMs: Long, audioTrackId: String? = null, subtitleTrackId: String? = null, subtitleOffsetMs: Long? = null, orientation: Int? = null, v2LoopMode: String? = null, v2NativeSubtitleTrackId: String? = null)`
  - `interface PlaybackMemoryDao { suspend fun upsert(entity); suspend fun getByUri(uri: String): PlaybackMemoryEntity? }`
  - `abstract class AppDatabase : RoomDatabase { fun playbackMemoryDao(): PlaybackMemoryDao; companion object { fun getInstance(context: Context): AppDatabase } }`

- [ ] **Step 1: Add Room + KSP + DataStore to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
room = "2.6.1"
ksp = "2.0.21-1.0.28"
datastore = "1.1.1"
```
Under `[libraries]` add:
```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```
Under `[plugins]` add:
```toml
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
> If Gradle reports the KSP version cannot be resolved, the KSP release must match the Kotlin version `2.0.21`. Pick the latest `2.0.21-1.0.x` from https://github.com/google/ksp/releases (e.g. `2.0.21-1.0.27`) and update the `ksp` version. Do not change the Kotlin version.

- [ ] **Step 2: Apply KSP + add deps + test deps in `app/build.gradle.kts`**

Add to the `plugins { }` block (after `compose.compiler`):
```kotlin
    alias(libs.plugins.ksp)
```
Add to `dependencies { }` (with the other `implementation`s):
```kotlin
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
```
Add to `dependencies { }` (with the `testImplementation`s):
```kotlin
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
```
Add inside the `android { }` block (after `buildFeatures { }`):
```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
```

- [ ] **Step 3: Write the failing DAO test**

`app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDaoTest.kt`:
```kotlin
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
class PlaybackMemoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlaybackMemoryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playbackMemoryDao()
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun `missing uri returns null`() = runTest {
        assertThat(dao.getByUri("nope")).isNull()
    }

    @Test fun `upsert then read round-trips`() = runTest {
        val e = PlaybackMemoryEntity("file:///a.mp4", 30_000, 120_000, "FILL", 1.5f, 111L)
        dao.upsert(e)
        assertThat(dao.getByUri("file:///a.mp4")).isEqualTo(e)
    }

    @Test fun `upsert replaces an existing row`() = runTest {
        dao.upsert(PlaybackMemoryEntity("u", 10, 100, "FIT", 1f, 1L))
        dao.upsert(PlaybackMemoryEntity("u", 50, 100, "ZOOM", 2f, 2L))
        val row = dao.getByUri("u")!!
        assertThat(row.positionMs).isEqualTo(50)
        assertThat(row.aspectMode).isEqualTo("ZOOM")
    }

    @Test fun `forward-compat reserved columns round-trip`() = runTest {
        val e = PlaybackMemoryEntity(
            mediaUri = "u", positionMs = 1, durationMs = 2, aspectMode = "FIT",
            speed = 1f, updatedAtEpochMs = 0L,
            audioTrackId = "a1", subtitleTrackId = "s1", subtitleOffsetMs = -50,
            orientation = 0, v2LoopMode = "AB", v2NativeSubtitleTrackId = "n1",
        )
        dao.upsert(e)
        assertThat(dao.getByUri("u")).isEqualTo(e)
    }
}
```

- [ ] **Step 4: Run to verify it fails (types unresolved)**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryDaoTest"`
Expected: FAIL — `PlaybackMemoryEntity` / `AppDatabase` / `PlaybackMemoryDao` unresolved.

- [ ] **Step 5: Implement the entity**

`app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryEntity.kt`:
```kotlin
package com.videoplayer.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-file playback memory, keyed by the media content/URI string we play.
 *
 * Wired in V1 (P1.C): [positionMs], [durationMs], [aspectMode], [speed], [updatedAtEpochMs].
 * Reserved (nullable, no migration needed when a later phase starts writing them):
 *  - [audioTrackId], [subtitleTrackId], [subtitleOffsetMs] — P1.G (subtitles/audio tracks)
 *  - [orientation] — P1.E (per-file orientation lock; ActivityInfo.screenOrientation int)
 *  - [v2LoopMode], [v2NativeSubtitleTrackId] — V2 language-learning; documented only, no logic.
 */
@Entity(tableName = "playback_memory")
data class PlaybackMemoryEntity(
    @PrimaryKey val mediaUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val aspectMode: String,
    val speed: Float,
    val updatedAtEpochMs: Long,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val subtitleOffsetMs: Long? = null,
    val orientation: Int? = null,
    val v2LoopMode: String? = null,
    val v2NativeSubtitleTrackId: String? = null,
)
```

- [ ] **Step 6: Implement the DAO**

`app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDao.kt`:
```kotlin
package com.videoplayer.app.data.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlaybackMemoryDao {
    @Upsert
    suspend fun upsert(entity: PlaybackMemoryEntity)

    @Query("SELECT * FROM playback_memory WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PlaybackMemoryEntity?
}
```

- [ ] **Step 7: Implement the database**

`app/src/main/kotlin/com/videoplayer/app/data/memory/AppDatabase.kt`:
```kotlin
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for playback persistence.
 *
 * `exportSchema = false`: there is a single schema version and no migrations yet.
 * When the schema version is bumped (a non-reserved column change), flip this to
 * `true`, add a `schemas/` dir + `room.schemaLocation` KSP arg, and provide a Migration.
 * Adding values to the reserved nullable columns does NOT require a version bump.
 */
@Database(entities = [PlaybackMemoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackMemoryDao(): PlaybackMemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_player.db",
                ).build().also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 8: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryDaoTest"`
Expected: PASS (4 tests). KSP generates the Room implementation during the build.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/com/videoplayer/app/data/memory app/src/test/kotlin/com/videoplayer/app/data/memory
git commit -m "feat(app): Room playback-memory entity, DAO, and database with forward-compat schema"
```

---

## Task C3: Settings + memory repository with precedence (`:app/data/memory`) [TDD]

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/ResolvedStartSettings.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepositoryTest.kt`

**Interfaces:**
- Consumes: `PlaybackMemoryDao`, `PlaybackMemoryEntity` (C2); `effectiveResumePosition`, `resolvePreference` (C1).
- Produces:
  - `data class ResolvedStartSettings(startPositionMs: Long, speed: Float, aspectMode: String)`
  - `val Context.settingsDataStore: DataStore<Preferences>` (top-level property delegate)
  - `class SettingsRepository(dataStore: DataStore<Preferences>) { val resumeEnabled: Flow<Boolean>; val defaultSpeed: Flow<Float>; suspend fun setResumeEnabled(Boolean); suspend fun setDefaultSpeed(Float) }`
  - `class PlaybackMemoryRepository(dao: PlaybackMemoryDao, settings: SettingsRepository) { suspend fun resolveStart(mediaUri: String): ResolvedStartSettings; suspend fun persist(mediaUri: String, positionMs: Long, durationMs: Long, speed: Float, aspectMode: String, nowEpochMs: Long) }`

- [ ] **Step 1: Implement `ResolvedStartSettings`**

`app/src/main/kotlin/com/videoplayer/app/data/memory/ResolvedStartSettings.kt`:
```kotlin
package com.videoplayer.app.data.memory

/** The settings the player should apply when a file opens, after precedence + resume policy. */
data class ResolvedStartSettings(
    val startPositionMs: Long,
    val speed: Float,
    val aspectMode: String,
)
```

- [ ] **Step 2: Implement `SettingsRepository` + DataStore delegate**

`app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt`:
```kotlin
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-wide settings DataStore. One instance per process, keyed off the app context. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global playback defaults (the lowest precedence tier). No settings UI exists yet
 * (P1.H) — these read their defaults today and are forward-ready for a settings screen.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val RESUME_ENABLED = booleanPreferencesKey("resume_enabled")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
    }

    val resumeEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.RESUME_ENABLED] ?: true }
    val defaultSpeed: Flow<Float> = dataStore.data.map { it[Keys.DEFAULT_SPEED] ?: 1f }

    suspend fun setResumeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RESUME_ENABLED] = enabled }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        dataStore.edit { it[Keys.DEFAULT_SPEED] = speed }
    }
}
```

- [ ] **Step 3: Write the failing repository test**

`app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepositoryTest.kt`:
```kotlin
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackMemoryRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: PlaybackMemoryRepository
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) {
            tmp.newFile("settings.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        repo = PlaybackMemoryRepository(db.playbackMemoryDao(), settings)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun `resolveStart with no saved row uses global defaults`() = runTest {
        val r = repo.resolveStart("file:///new.mp4")
        assertThat(r.startPositionMs).isEqualTo(0L)
        assertThat(r.speed).isEqualTo(1f)
        assertThat(r.aspectMode).isEqualTo("FIT")
    }

    @Test fun `persist then resolveStart resumes saved position, aspect, and speed`() = runTest {
        repo.persist("u", positionMs = 40_000, durationMs = 120_000, speed = 1.5f, aspectMode = "ZOOM", nowEpochMs = 99L)
        val r = repo.resolveStart("u")
        assertThat(r.startPositionMs).isEqualTo(40_000L)
        assertThat(r.aspectMode).isEqualTo("ZOOM")  // per-file overrides global
        assertThat(r.speed).isEqualTo(1.5f)
    }

    @Test fun `resolveStart returns zero position when resume is disabled`() = runTest {
        repo.persist("u", positionMs = 40_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT", nowEpochMs = 1L)
        settings.setResumeEnabled(false)
        assertThat(repo.resolveStart("u").startPositionMs).isEqualTo(0L)
    }

    @Test fun `near-end saved position restarts from zero`() = runTest {
        repo.persist("u", positionMs = 119_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT", nowEpochMs = 1L)
        assertThat(repo.resolveStart("u").startPositionMs).isEqualTo(0L)
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryRepositoryTest"`
Expected: FAIL — `PlaybackMemoryRepository` unresolved.

- [ ] **Step 5: Implement `PlaybackMemoryRepository`**

`app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt`:
```kotlin
package com.videoplayer.app.data.memory

import com.videoplayer.core.playback.effectiveResumePosition
import com.videoplayer.core.playback.resolvePreference
import kotlinx.coroutines.flow.first

/**
 * Resolves what settings to apply when a file opens, and persists state when it closes.
 *
 * Precedence (per-file → folder → global) via [resolvePreference]. The folder tier is
 * `null` in V1 (no folder-default setter yet); it is fully unit-tested in C1 and wired
 * here for when P1.D/P1.E add folder defaults. Resume position runs through the pure
 * [effectiveResumePosition] policy and is gated by the global resume-enabled setting.
 */
class PlaybackMemoryRepository(
    private val dao: PlaybackMemoryDao,
    private val settings: SettingsRepository,
) {
    suspend fun resolveStart(mediaUri: String): ResolvedStartSettings {
        val saved = dao.getByUri(mediaUri)
        val resumeEnabled = settings.resumeEnabled.first()
        val globalSpeed = settings.defaultSpeed.first()

        val speed = resolvePreference(file = saved?.speed, folder = null, global = globalSpeed)
        val aspectMode = resolvePreference(file = saved?.aspectMode, folder = null, global = "FIT")
        val startPositionMs = if (resumeEnabled && saved != null) {
            effectiveResumePosition(saved.positionMs, saved.durationMs)
        } else {
            0L
        }
        return ResolvedStartSettings(startPositionMs, speed, aspectMode)
    }

    suspend fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
        nowEpochMs: Long,
    ) {
        val existing = dao.getByUri(mediaUri)
        val base = existing ?: PlaybackMemoryEntity(
            mediaUri = mediaUri, positionMs = 0, durationMs = 0,
            aspectMode = "FIT", speed = 1f, updatedAtEpochMs = 0L,
        )
        dao.upsert(
            base.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                aspectMode = aspectMode,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}
```
> Note: `existing?.copy(...)` preserves any reserved columns a future phase wrote (audio/subtitle track, orientation) when P1.C re-saves position — we never null them out.

- [ ] **Step 6: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryRepositoryTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/memory app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepositoryTest.kt
git commit -m "feat(app): settings + playback-memory repository with file>folder>global precedence"
```

---

## Task C4: PlayerViewModel + PlayerScreen wiring (silent auto-resume) [TDD + Android-verify]

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/player/PlayerViewModelTest.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `PlaybackMemoryRepository`, `SettingsRepository`, `AppDatabase`, `settingsDataStore`, `ResolvedStartSettings` (C2/C3); `Media3PlaybackEngine`, `AspectMode` (existing).
- Produces:
  - `class PlayerViewModel(repo: PlaybackMemoryRepository) : ViewModel() { val resolved: StateFlow<ResolvedStartSettings?>; fun load(mediaUri: String); fun persist(mediaUri, positionMs, durationMs, speed, aspectMode) }`
  - `PlayerViewModel.factory(context: Context): ViewModelProvider.Factory`

- [ ] **Step 1: Write the failing ViewModel test**

`app/src/test/kotlin/com/videoplayer/app/player/PlayerViewModelTest.kt`:
```kotlin
package com.videoplayer.app.player

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.PlaybackMemoryRepository
import com.videoplayer.app.data.memory.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: PlaybackMemoryRepository
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) {
            tmp.newFile("settings.preferences_pb")
        }
        repo = PlaybackMemoryRepository(db.playbackMemoryDao(), SettingsRepository(dataStore))
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun `load publishes resolved defaults for an unknown file`() = runTest {
        val vm = PlayerViewModel(repo)
        vm.load("file:///unknown.mp4")
        advanceUntilIdle()
        val r = vm.resolved.value!!
        assertThat(r.startPositionMs).isEqualTo(0L)
        assertThat(r.aspectMode).isEqualTo("FIT")
        assertThat(r.speed).isEqualTo(1f)
    }

    @Test fun `persist then load resumes the saved position`() = runTest {
        val vm = PlayerViewModel(repo)
        vm.persist("u", positionMs = 42_000, durationMs = 120_000, speed = 1f, aspectMode = "FILL")
        advanceUntilIdle()
        vm.load("u")
        advanceUntilIdle()
        val r = vm.resolved.value!!
        assertThat(r.startPositionMs).isEqualTo(42_000L)
        assertThat(r.aspectMode).isEqualTo("FILL")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.PlayerViewModelTest"`
Expected: FAIL — `PlayerViewModel` unresolved.

- [ ] **Step 3: Implement `PlayerViewModel`**

`app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt`:
```kotlin
package com.videoplayer.app.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.PlaybackMemoryRepository
import com.videoplayer.app.data.memory.ResolvedStartSettings
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.memory.settingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns playback persistence for the player screen: loads the resolved start settings
 * when a file opens (silent auto-resume) and writes current state back as it changes.
 */
class PlayerViewModel(private val repo: PlaybackMemoryRepository) : ViewModel() {

    private val _resolved = MutableStateFlow<ResolvedStartSettings?>(null)
    val resolved: StateFlow<ResolvedStartSettings?> = _resolved.asStateFlow()

    fun load(mediaUri: String) {
        viewModelScope.launch { _resolved.value = repo.resolveStart(mediaUri) }
    }

    fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
    ) {
        viewModelScope.launch {
            repo.persist(mediaUri, positionMs, durationMs, speed, aspectMode, System.currentTimeMillis())
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val settings = SettingsRepository(appContext.settingsDataStore)
                PlayerViewModel(PlaybackMemoryRepository(db.playbackMemoryDao(), settings))
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.PlayerViewModelTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit the ViewModel**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt app/src/test/kotlin/com/videoplayer/app/player/PlayerViewModelTest.kt
git commit -m "feat(app): PlayerViewModel loading and persisting playback memory with tests"
```

- [ ] **Step 6: Wire `PlayerViewModel` into `PlayerScreen`**

Edit `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`.

(a) Add imports (with the other imports):
```kotlin
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoplayer.app.data.memory.ResolvedStartSettings
import com.videoplayer.core.playback.PlayerStatus
```

(b) Inside `PlayerScreen`, right after `val state by engine.state.collectAsStateWithLifecycle()` (line ~72), add the ViewModel + resume state:
```kotlin
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(context))
    val resolved by playerViewModel.resolved.collectAsStateWithLifecycle()
    var resumeApplied by remember(item.uri) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
```

(c) Replace the existing media-loading `LaunchedEffect(engine)` block:
```kotlin
    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
        engine.play()
    }
```
with one that loads memory first and defers `play()` until resume is applied:
```kotlin
    LaunchedEffect(item.uri) {
        playerViewModel.load(item.uri)
    }
    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
    }
    // Apply resolved settings once the engine is READY (duration known) and start playing.
    LaunchedEffect(state.status, resolved) {
        val r = resolved
        if (!resumeApplied && r != null && state.status == PlayerStatus.READY) {
            if (r.startPositionMs > 0) engine.seekTo(r.startPositionMs)
            engine.setSpeed(r.speed)
            aspectMode = runCatching { AspectMode.valueOf(r.aspectMode) }.getOrDefault(AspectMode.FIT)
            resumeApplied = true
            engine.play()
        }
    }
```

(d) Add periodic + lifecycle save. Place after the existing auto-hide `LaunchedEffect` blocks (before the `Box`). Use `rememberUpdatedState` so the lifecycle observer reads the latest values:
```kotlin
    val latestAspect by rememberUpdatedState(aspectMode)
    val latestBoost by rememberUpdatedState(speedBoostActive)

    // Save current state: periodically while playing, and on STOP / dispose.
    fun saveNow() {
        if (!resumeApplied) return
        val s = engine.state.value
        val speedToSave = if (latestBoost) 1f else s.speed
        playerViewModel.persist(
            mediaUri = item.uri,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            speed = speedToSave,
            aspectMode = latestAspect.name,
        )
    }

    LaunchedEffect(state.isPlaying, resumeApplied) {
        if (state.isPlaying && resumeApplied) {
            while (true) {
                delay(5_000L)
                saveNow()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveNow()
        }
    }
```
> `saveNow()` is a local function capturing `engine`, `item`, `playerViewModel`, `latestAspect`, `latestBoost`, `resumeApplied`. It must be declared after `resumeApplied`, `latestAspect`, and `latestBoost`. The boost guard prevents persisting the transient 2× long-press speed.

- [ ] **Step 7: [Android-verify] Build the debug APK**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: [Android-verify] Resume smoke test on the emulator**

Generate a 60s test clip (the existing 5s clip is too short for the 5s resume threshold), push, scan, then verify resume:
```bash
export PATH="/opt/homebrew/bin:$PATH"
ffmpeg -y -f lavfi -i testsrc=duration=60:size=640x360:rate=30 -pix_fmt yuv420p /tmp/resume60.mp4
"$HOME/Library/Android/sdk/emulator/emulator" -avd kuran_test -no-snapshot -no-boot-anim &
adb wait-for-device
adb shell rm -f /sdcard/Movies/resume60.mp4
adb push /tmp/resume60.mp4 /sdcard/Movies/resume60.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/resume60.mp4
./gradlew :app:installDebug
adb shell am start -n com.videoplayer.app/.MainActivity
```
Manual/scripted check:
1. Tap `resume60.mp4`, let it play ~20s, press Back to the library.
2. Re-open `resume60.mp4`.
3. Confirm playback resumes near ~20s (not 0): `adb exec-out screencap -p > /tmp/resume_check.png` and inspect the scrubber position; and/or grep logcat for the engine position.
4. Aspect persistence: in the player cycle aspect to FILL (control button), Back, re-open → opens in FILL.

Expected: re-opening resumes at the saved position and remembers the aspect mode; no crash in `adb logcat -d | grep -i AndroidRuntime`.

- [ ] **Step 9: Commit — P1.C complete**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(player): silent auto-resume and per-file aspect/speed memory via PlayerViewModel"
```

---

## Self-Review (against the P1.C spec)

**Spec coverage:**
- *Room DB + DataStore* → C2 (Room), C3 (DataStore `SettingsRepository`). ✅
- *Per-file: resume position, speed, audio track, subtitle track, zoom/aspect, orientation* → wired now: position, speed, aspect (C2 columns + C3/C4 wiring). Reserved nullable now: audioTrackId, subtitleTrackId, subtitleOffsetMs (P1.G), orientation (P1.E). ✅ (honest scoping — those features don't exist yet)
- *Folder-level defaults* → precedence resolver built + tested (C1); folder *table/setter* deferred with documented rationale (no setter exists). The `folder` tier is wired through `resolveStart`. ✅
- *V2 forward-compatible fields (nullable, reserved, documented)* → `v2LoopMode`, `v2NativeSubtitleTrackId` columns + the forward-compat round-trip test in C2. ✅
- *[TDD] DAO logic (in-memory Room)* → C2 `PlaybackMemoryDaoTest`. ✅
- *[TDD] resume-vs-restart threshold math* → C1 `ResumePolicyTest`. ✅
- *[TDD] folder-default resolution precedence (file > folder > global)* → C1 `PreferencePrecedenceTest` + C3 repository test. ✅
- *[TDD] forward-compat schema test* → C2 reserved-columns round-trip test. ✅
- *[Android-verify] resume actually works (play, exit, reopen → resumes)* → C4 Step 8. ✅
- *Decision flag: resume threshold + ask-vs-auto-resume UX* → resolved (silent auto-resume, 5s/5s-from-end thresholds) per user-confirmed decision in Global Constraints. ✅

**Placeholder scan:** every code step contains complete code; commands have expected output; no TBD/TODO. ✅

**Type consistency:** `PlaybackMemoryEntity`, `PlaybackMemoryDao.getByUri/upsert`, `AppDatabase.getInstance/playbackMemoryDao`, `SettingsRepository.resumeEnabled/defaultSpeed/setResumeEnabled/setDefaultSpeed`, `PlaybackMemoryRepository.resolveStart/persist`, `ResolvedStartSettings(startPositionMs, speed, aspectMode)`, `PlayerViewModel.load/persist/resolved/factory`, `effectiveResumePosition`, `resolvePreference`, `AspectMode.valueOf` — all used consistently across tasks. ✅
