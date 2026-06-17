# Video Player — Phase 0 + Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a buildable, installable, ad-free Android video player skeleton with clean UI-agnostic module boundaries, a `PlaybackEngine` abstraction, a Media3 implementation, a MediaStore-backed media library, and basic playback (Phase 0) — then grow it into a genuinely good lean player (gestures, smart memory, PiP, background audio, basic subtitles, Material You) ready for a first F-Droid release (Phase 1).

**Architecture:** Multi-module Gradle. Core logic lives in pure-Kotlin, Android-free, JVM-unit-testable modules (`:core:model`, `:core:playback`) so it can move to KMP later with only the UI rewritten. The Android app module (`:app`) holds the Compose UI, the Media3 engine implementation, and the MediaStore data source. Everything talks to playback through a single `PlaybackEngine` interface; the UI never knows which engine is running (this is what lets libmpv slot in as a second engine in Phase 2 with zero UI change).

**Tech Stack:** Kotlin 2.0.x · Jetpack Compose (Material3 / Material You) · AndroidX Media3 (ExoPlayer) · Gradle (wrapper) + Kotlin DSL + version catalog · JDK 21 (Android Studio bundled JBR) · Room + DataStore (Phase 1 persistence) · JUnit4 + Truth + kotlinx-coroutines-test + Turbine + Robolectric (tests).

## Global Constraints

- **Platform:** Native Android, Kotlin + Jetpack Compose only. No Flutter/RN.
- **License:** GPLv3. Distribution: F-Droid first, then Play Store.
- **Core is UI-agnostic:** `:core:*` modules MUST NOT depend on `androidx.compose.*`, `android.*` UI, or any Activity/Context-UI type. They may use coroutines/Flow and plain Kotlin only. (MediaStore/Context-dependent code lives in `:app`, behind a core-defined interface.)
- **Zero telemetry, zero ads, no analytics SDKs, no account/network calls** beyond explicit user-initiated media playback. No tracking libraries enter the dependency graph.
- **Min permissions:** request only `READ_MEDIA_VIDEO` (API 33+) / `READ_EXTERNAL_STORAGE` (≤32) and post-notifications (API 33+) when background audio lands. Nothing speculative.
- **Toolchain (verified present):** SDK at `~/Library/Android/sdk` (compileSdk 35, build-tools 35). Build with JDK 21 via `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Emulator AVD `kuran_test` available for smoke tests.
- **minSdk 24, targetSdk 35, compileSdk 35.** (PiP gestures gate to API 26+ at runtime; Phase 1 notes where.)
- **Commit after every green step.** Small, focused, conventional-commit messages.
- **TDD discipline:** pure-logic tasks follow red→green→commit with JVM unit tests. Android-integration tasks (engine wiring, Compose screens) that cannot be classic-unit-tested are verified by `assembleDebug` + an emulator smoke check and/or Robolectric, and the plan marks them explicitly as **[Android-verify]** rather than pretending they are red-green unit tests.

---

## Test Strategy — what "green" means here

Android has two honest testing tiers, and this plan uses both deliberately:

1. **[TDD] pure-logic tasks** — code in `:core:*` and any pure function extracted into testable shape. Real red→green→refactor with `./gradlew :core:model:test` etc. These run headless, fast, no emulator. The bulk of business logic (state transitions, folder grouping/filtering, resume math, gesture→action mapping, subtitle sibling matching) is deliberately written this way.
2. **[Android-verify] integration tasks** — Media3 engine, Compose screens, MediaStore queries, PiP/service. Verified by: `./gradlew assembleDebug` succeeds **and** a scripted emulator smoke (`adb install` + launch + a logcat/UiAutomator assertion) and/or Robolectric where it adds value. These are committed when the build is green and the smoke passes; the plan states the exact check per task.

We never claim a task done without running its stated verification command and seeing it pass.

---

## File Structure (Phase 0)

```
video-player/
├── settings.gradle.kts            # module includes, repositories
├── build.gradle.kts               # root, plugin versions via catalog
├── gradle/libs.versions.toml      # version catalog (single source of dep versions)
├── gradle.properties              # JVM args, AndroidX, org.gradle.java flags
├── gradlew / gradlew.bat / gradle/wrapper/*   # Gradle wrapper
├── .gitignore
├── LICENSE                        # GPLv3
├── core/
│   ├── model/                     # :core:model — pure Kotlin, no Android
│   │   ├── build.gradle.kts       # java-library + kotlin("jvm")
│   │   └── src/main/kotlin/com/videoplayer/core/model/
│   │       ├── MediaItem.kt
│   │       ├── MediaFolder.kt
│   │       └── Duration.kt        # formatDuration() pure helper
│   │   └── src/test/kotlin/...    # JVM unit tests
│   └── playback/                  # :core:playback — pure Kotlin
│       ├── build.gradle.kts
│       └── src/main/kotlin/com/videoplayer/core/playback/
│           ├── PlaybackEngine.kt  # the interface
│           ├── PlaybackState.kt   # data + enums
│           ├── EngineType.kt
│           └── FakePlaybackEngine.kt   # test double (in main so :app tests can reuse)
│       └── src/test/kotlin/...
└── app/                           # :app — Android, Compose, Media3, MediaStore
    ├── build.gradle.kts           # com.android.application + kotlin.android + compose
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/kotlin/com/videoplayer/app/
        │   ├── MainActivity.kt
        │   ├── VideoPlayerApp.kt          # Compose root + nav
        │   ├── theme/                     # Material You theme
        │   ├── engine/Media3PlaybackEngine.kt   # implements PlaybackEngine
        │   ├── data/MediaStoreRepository.kt     # implements MediaRepository
        │   ├── library/LibraryScreen.kt
        │   └── player/PlayerScreen.kt
        ├── test/kotlin/...                # Robolectric / pure unit tests
        └── androidTest/kotlin/...         # instrumented smoke (optional)
```

**Future split points (do NOT pre-build):** when libmpv arrives (Phase 2), `Media3PlaybackEngine` and the new `MpvPlaybackEngine` graduate into `:engine:media3` and `:engine:mpv` modules. When persistence grows (Phase 1), data sources may move to `:core:data`. We split when a module earns it, not before (Simplicity first).

---

## PHASE 0 — Foundation (fully detailed, task-by-task)

**Phase 0 done =** app installs and launches on the emulator, shows a list of videos found via MediaStore (and/or a SAF-picked file), and plays a selected video through the Media3 engine behind the `PlaybackEngine` interface, with all `:core:*` logic JVM-unit-tested and green.

### Task 0.1: Repository + Gradle scaffold

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `LICENSE`
- Create: Gradle wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`)

**Interfaces:**
- Produces: a working Gradle build (`./gradlew help` succeeds) and the version catalog other tasks read versions from.

- [ ] **Step 1: `git init` and write `.gitignore`**

```bash
cd "/Users/mustafa/Desktop/Projects/mobil uygulama/video-player"
git init
git branch -m main
```

`.gitignore` (Android standard):
```
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
**/build/
*.apk
*.keystore
!debug.keystore
```

- [ ] **Step 2: Generate the Gradle wrapper pinned to a known-good version**

Use the system... there is no `gradle` on PATH, so bootstrap the wrapper via Android Studio's bundled Gradle is awkward; instead write `gradle/wrapper/gradle-wrapper.properties` pointing at Gradle 8.11.1 and fetch the wrapper jar:

```bash
# gradle-wrapper.properties distributionUrl -> gradle-8.11.1-bin.zip
# Fetch gradle-wrapper.jar + gradlew from the Gradle 8.11.1 distribution (curl the jar from services.gradle.org or copy from an existing wrapper).
```
`gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: Write `gradle/libs.versions.toml` (version catalog)**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
media3 = "1.5.1"
junit = "4.13.2"
truth = "1.4.4"
coroutines = "1.9.0"
turbine = "1.2.0"
robolectric = "4.14"
androidxTest = "1.6.1"
androidxJunit = "1.2.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4: Root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `LICENSE`**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VideoPlayer"
include(":app", ":core:model", ":core:playback")
```
`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```
`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```
`LICENSE`: full GNU GPLv3 text (fetch verbatim).

Write `local.properties` (gitignored) with `sdk.dir=/Users/mustafa/Library/Android/sdk`.

- [ ] **Step 5: Verify the build wiring and commit**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew help`
Expected: `BUILD SUCCESSFUL`.
```bash
git add -A && git commit -m "chore: scaffold Gradle multi-module project with version catalog"
```

---

### Task 0.2: `:core:model` — media data models (TDD)

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/kotlin/com/videoplayer/core/model/Duration.kt`, `MediaItem.kt`, `MediaFolder.kt`
- Test: `core/model/src/test/kotlin/com/videoplayer/core/model/DurationTest.kt`, `MediaFolderTest.kt`

**Interfaces:**
- Produces:
  - `data class MediaItem(val id: Long, val uri: String, val displayName: String, val folderPath: String, val durationMs: Long, val sizeBytes: Long, val dateAddedSec: Long)`
  - `data class MediaFolder(val path: String, val name: String, val items: List<MediaItem>) { val videoCount: Int }`
  - `fun formatDuration(ms: Long): String` — `"0:09"`, `"1:05:09"`, clamps negatives to `"0:00"`.

- [ ] **Step 1: Write the failing test for `formatDuration`**

```kotlin
package com.videoplayer.core.model
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DurationTest {
    @Test fun `formats seconds under a minute`() {
        assertThat(formatDuration(9_000)).isEqualTo("0:09")
    }
    @Test fun `formats minutes and seconds`() {
        assertThat(formatDuration(65_000)).isEqualTo("1:05")
    }
    @Test fun `formats hours`() {
        assertThat(formatDuration(3_909_000)).isEqualTo("1:05:09")
    }
    @Test fun `clamps negative to zero`() {
        assertThat(formatDuration(-5)).isEqualTo("0:00")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:model:test`
Expected: FAIL — `formatDuration` unresolved (and module build file must exist).

First make it *compile-fail* into *test-fail* by creating `core/model/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 3: Implement `Duration.kt`**

```kotlin
package com.videoplayer.core.model

fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `... ./gradlew :core:model:test`
Expected: PASS (4 tests).

- [ ] **Step 5: Add `MediaItem` + `MediaFolder` with a `videoCount` test**

```kotlin
// MediaItem.kt
package com.videoplayer.core.model
data class MediaItem(
    val id: Long, val uri: String, val displayName: String,
    val folderPath: String, val durationMs: Long,
    val sizeBytes: Long, val dateAddedSec: Long,
)
// MediaFolder.kt
package com.videoplayer.core.model
data class MediaFolder(val path: String, val name: String, val items: List<MediaItem>) {
    val videoCount: Int get() = items.size
}
```
```kotlin
// MediaFolderTest.kt
class MediaFolderTest {
    @Test fun `videoCount reflects item list size`() {
        val folder = MediaFolder("/movies", "movies", listOf(sample(1), sample(2)))
        assertThat(folder.videoCount).isEqualTo(2)
    }
    private fun sample(id: Long) = MediaItem(id, "uri$id", "v$id.mp4", "/movies", 1000, 10, 0)
}
```

- [ ] **Step 6: Run + commit**

Run: `... ./gradlew :core:model:test` → PASS.
```bash
git add core/model && git commit -m "feat(core): add media models and duration formatter with tests"
```

---

### Task 0.3: `:core:playback` — `PlaybackEngine` interface + `FakePlaybackEngine` (TDD)

**Files:**
- Create: `core/playback/build.gradle.kts`
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/{EngineType,PlaybackState,PlaybackEngine,FakePlaybackEngine}.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/FakePlaybackEngineTest.kt`

**Interfaces:**
- Produces:
  - `enum class EngineType { MEDIA3, MPV }`
  - `enum class PlayerStatus { IDLE, BUFFERING, READY, ENDED }`
  - `data class PlaybackState(val status: PlayerStatus = IDLE, val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val speed: Float = 1f, val engine: EngineType = MEDIA3)`
  - ```kotlin
    interface PlaybackEngine {
        val state: StateFlow<PlaybackState>
        fun setMediaUri(uri: String)
        fun play(); fun pause(); fun seekTo(positionMs: Long)
        fun setSpeed(speed: Float)
        fun release()
    }
    ```
  - `class FakePlaybackEngine : PlaybackEngine` — deterministic in-memory implementation used by core tests and (later) by UI previews/tests.
- Consumes: nothing (foundation).

- [ ] **Step 1: Write the failing contract test against `FakePlaybackEngine`**

```kotlin
package com.videoplayer.core.playback
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakePlaybackEngineTest {
    @Test fun `starts idle and not playing`() = runTest {
        val e = FakePlaybackEngine()
        assertThat(e.state.value.status).isEqualTo(PlayerStatus.IDLE)
        assertThat(e.state.value.isPlaying).isFalse()
    }
    @Test fun `setMediaUri moves to ready with duration`() = runTest {
        val e = FakePlaybackEngine(fakeDurationMs = 60_000)
        e.setMediaUri("file:///a.mp4")
        assertThat(e.state.value.status).isEqualTo(PlayerStatus.READY)
        assertThat(e.state.value.durationMs).isEqualTo(60_000)
    }
    @Test fun `play then pause toggles isPlaying`() = runTest {
        val e = FakePlaybackEngine(); e.setMediaUri("u")
        e.play(); assertThat(e.state.value.isPlaying).isTrue()
        e.pause(); assertThat(e.state.value.isPlaying).isFalse()
    }
    @Test fun `seekTo clamps within 0..duration`() = runTest {
        val e = FakePlaybackEngine(fakeDurationMs = 1000); e.setMediaUri("u")
        e.seekTo(5000); assertThat(e.state.value.positionMs).isEqualTo(1000)
        e.seekTo(-10); assertThat(e.state.value.positionMs).isEqualTo(0)
    }
    @Test fun `setSpeed updates state`() = runTest {
        val e = FakePlaybackEngine(); e.setSpeed(2f)
        assertThat(e.state.value.speed).isEqualTo(2f)
    }
}
```

- [ ] **Step 2: Create build file + run to verify fail**

`core/playback/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```
Run: `... ./gradlew :core:playback:test` → FAIL (types unresolved).

- [ ] **Step 3: Implement the types + `FakePlaybackEngine`**

```kotlin
// EngineType.kt
package com.videoplayer.core.playback
enum class EngineType { MEDIA3, MPV }

// PlaybackState.kt
package com.videoplayer.core.playback
enum class PlayerStatus { IDLE, BUFFERING, READY, ENDED }
data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val engine: EngineType = EngineType.MEDIA3,
)

// PlaybackEngine.kt
package com.videoplayer.core.playback
import kotlinx.coroutines.flow.StateFlow
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun setMediaUri(uri: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}

// FakePlaybackEngine.kt
package com.videoplayer.core.playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
class FakePlaybackEngine(private val fakeDurationMs: Long = 0) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override fun setMediaUri(uri: String) =
        _state.update { it.copy(status = PlayerStatus.READY, durationMs = fakeDurationMs, positionMs = 0) }
    override fun play() = _state.update { it.copy(isPlaying = true) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) =
        _state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }
    override fun release() = _state.update { PlaybackState() }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `... ./gradlew :core:playback:test` → PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/playback && git commit -m "feat(core): define PlaybackEngine interface, state model, and fake engine with tests"
```

---

### Task 0.4: `:app` skeleton — Compose + Material You theme, launches **[Android-verify]**

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/videoplayer/app/{MainActivity,VideoPlayerApp}.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/theme/{Color,Theme,Type}.kt`
- Create: `app/src/main/res/values/{strings,themes}.xml`, mipmap launcher (use default adaptive icon)

**Interfaces:**
- Consumes: nothing yet (UI shell).
- Produces: `MainActivity` hosting `VideoPlayerApp()`; `AppTheme { }` composable applying Material You dynamic color (API 31+) with a true-black AMOLED dark scheme fallback.

- [ ] **Step 1: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.videoplayer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.videoplayer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:playback"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}
```

- [ ] **Step 2: Manifest + theme + MainActivity + a "Hello, Player" scaffold**

`AndroidManifest.xml` (single Activity, no internet permission yet):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name" android:theme="@style/Theme.VideoPlayer"
                 android:allowBackup="false" android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
`MainActivity.kt` sets `VideoPlayerApp()` inside `AppTheme`; `VideoPlayerApp` renders a `Scaffold` with a placeholder `Text("Video Player")`. Theme uses `dynamicDarkColorScheme(context)` on API 31+ else a hand-defined true-black scheme (`background = Color.Black`).

- [ ] **Step 3: [Android-verify] Build the debug APK**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: [Android-verify] Install + launch on emulator, confirm it doesn't crash**

```bash
"$HOME/Library/Android/sdk/emulator/emulator" -avd kuran_test -no-snapshot -no-boot-anim &
adb wait-for-device; adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.videoplayer.app/.MainActivity
adb logcat -d | grep -i "AndroidRuntime" | grep -i "com.videoplayer" && echo "CRASH" || echo "OK: launched cleanly"
```
Expected: `OK: launched cleanly` and "Video Player" visible (screenshot via `adb exec-out screencap -p > /tmp/launch.png`).

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(app): Compose app skeleton with Material You/AMOLED theme that launches"
```

---

### Task 0.5: Media3 engine implementation behind `PlaybackEngine` **[Android-verify + Robolectric]**

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/engine/Media3StateMapperTest.kt` (Robolectric/pure)

**Interfaces:**
- Consumes: `PlaybackEngine`, `PlaybackState`, `PlayerStatus`, `EngineType` from `:core:playback`.
- Produces: `class Media3PlaybackEngine(context: Context) : PlaybackEngine` wrapping an `ExoPlayer`; and a pure `fun exoStateToStatus(playbackState: Int): PlayerStatus` mapper that IS unit-tested.

- [ ] **Step 1: [TDD] Failing test for the pure ExoPlayer→status mapper**

```kotlin
class Media3StateMapperTest {
    @Test fun `maps exo constants to PlayerStatus`() {
        assertThat(exoStateToStatus(Player.STATE_IDLE)).isEqualTo(PlayerStatus.IDLE)
        assertThat(exoStateToStatus(Player.STATE_BUFFERING)).isEqualTo(PlayerStatus.BUFFERING)
        assertThat(exoStateToStatus(Player.STATE_READY)).isEqualTo(PlayerStatus.READY)
        assertThat(exoStateToStatus(Player.STATE_ENDED)).isEqualTo(PlayerStatus.ENDED)
    }
}
```
Add `media3-exoplayer` to deps. Run `:app:testDebugUnitTest` → FAIL.

- [ ] **Step 2: Implement `Media3PlaybackEngine` + mapper, make the test pass**

`exoStateToStatus` is a top-level pure function. `Media3PlaybackEngine` builds an `ExoPlayer`, registers a `Player.Listener`, and pushes a `PlaybackState(engine = MEDIA3)` into a `MutableStateFlow` on every callback; `setMediaUri`/`play`/`pause`/`seekTo`/`setSpeed`/`release` delegate to ExoPlayer. Run `:app:testDebugUnitTest` → PASS.

- [ ] **Step 3: [Android-verify] Smoke-play a bundled sample on the emulator**

Push a tiny sample mp4 to the device, drive the engine from a temporary debug entry (or instrumented test) and assert state reaches `READY` then `isPlaying`. Minimal instrumented test in `androidTest` using `ApplicationProvider` + `IdlingResource`, or a manual `adb` push + screen check.
Run: `... ./gradlew :app:connectedDebugAndroidTest` (emulator running) → PASS, **or** documented manual smoke with screenshot.

- [ ] **Step 4: Commit**

```bash
git add app && git commit -m "feat(app): Media3 implementation of PlaybackEngine with tested state mapping"
```

---

### Task 0.6: MediaStore library scan behind a `MediaRepository` interface (TDD on the pure grouping)

**Files:**
- Create: `core/playback`… no — Create interface in `:core:model`: `core/model/src/main/kotlin/com/videoplayer/core/model/MediaRepository.kt`
- Create: `core/model/src/main/kotlin/com/videoplayer/core/model/FolderGrouping.kt` (pure)
- Create: `app/src/main/kotlin/com/videoplayer/app/data/MediaStoreRepository.kt` (Android impl)
- Test: `core/model/src/test/kotlin/com/videoplayer/core/model/FolderGroupingTest.kt`

**Interfaces:**
- Produces:
  - `interface MediaRepository { fun observeFolders(): Flow<List<MediaFolder>> ; suspend fun refresh() }`
  - pure `fun groupIntoFolders(items: List<MediaItem>): List<MediaFolder>` — groups by `folderPath`, sorts folders by name, drops empty, sorts items by `displayName`. **This is the TDD target.**
  - `class MediaStoreRepository(context: Context) : MediaRepository` — queries `MediaStore.Video`, maps cursor rows to `MediaItem`, calls `groupIntoFolders`.

- [ ] **Step 1: [TDD] Failing test for `groupIntoFolders`**

```kotlin
class FolderGroupingTest {
    @Test fun `groups items by folder and sorts`() {
        val items = listOf(
            MediaItem(1,"u1","b.mp4","/movies",0,0,0),
            MediaItem(2,"u2","a.mp4","/movies",0,0,0),
            MediaItem(3,"u3","x.mp4","/clips",0,0,0),
        )
        val folders = groupIntoFolders(items)
        assertThat(folders.map { it.name }).containsExactly("clips","movies").inOrder()
        assertThat(folders.first { it.name=="movies" }.items.map { it.displayName })
            .containsExactly("a.mp4","b.mp4").inOrder()
    }
    @Test fun `empty input yields empty list`() {
        assertThat(groupIntoFolders(emptyList())).isEmpty()
    }
}
```
Add `kotlinx-coroutines-core` to `:core:model` (for Flow in the interface). Run `:core:model:test` → FAIL.

- [ ] **Step 2: Implement `groupIntoFolders` + `MediaRepository`; make tests pass**

```kotlin
fun groupIntoFolders(items: List<MediaItem>): List<MediaFolder> =
    items.groupBy { it.folderPath }
        .map { (path, list) ->
            MediaFolder(path, path.substringAfterLast('/').ifEmpty { path },
                list.sortedBy { it.displayName.lowercase() })
        }
        .filter { it.items.isNotEmpty() }
        .sortedBy { it.name.lowercase() }
```
Run `:core:model:test` → PASS.

- [ ] **Step 3: Implement `MediaStoreRepository` (Android, no new unit test — covered by smoke)**

Query `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` for id/display_name/data/duration/size/date_added/bucket path; map to `MediaItem`; emit `groupIntoFolders(rows)`. Handle `READ_MEDIA_VIDEO` permission (request on first launch).

- [ ] **Step 4: [Android-verify] On emulator with a pushed sample video, folders list is non-empty**

```bash
adb push /tmp/sample.mp4 /sdcard/Movies/sample.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/sample.mp4
```
Drive `MediaStoreRepository.refresh()` (instrumented test) → assert ≥1 folder. Run `:app:connectedDebugAndroidTest` or manual.

- [ ] **Step 5: Commit**

```bash
git add core/model app && git commit -m "feat: MediaStore-backed media library with tested folder grouping"
```

---

### Task 0.7: Library screen → player screen, real playback wiring **[Android-verify]**

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt`, `LibraryViewModel.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`, `PlayerViewModel.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt` (nav between the two)
- Test: `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelTest.kt` (Robolectric, fake repo)

**Interfaces:**
- Consumes: `MediaRepository`, `MediaFolder`, `MediaItem`, `PlaybackEngine`.
- Produces: a list UI of folders/items; tapping an item navigates to `PlayerScreen` which feeds `item.uri` into a `Media3PlaybackEngine` and renders Media3 `PlayerView` (AndroidView) with play/pause.

- [ ] **Step 1: [TDD] `LibraryViewModel` exposes folders from a fake repo**

```kotlin
class LibraryViewModelTest {
    @Test fun `emits folders from repository`() = runTest {
        val repo = FakeMediaRepository(listOf(MediaFolder("/m","m", listOf(/*item*/))))
        val vm = LibraryViewModel(repo)
        vm.refresh()
        assertThat(vm.folders.value.map { it.name }).containsExactly("m")
    }
}
```
Run `:app:testDebugUnitTest` → FAIL.

- [ ] **Step 2: Implement `LibraryViewModel` (collects `repo.observeFolders()` into `StateFlow`), make pass.**

Run `:app:testDebugUnitTest` → PASS.

- [ ] **Step 3: Build `LibraryScreen` + `PlayerScreen` + nav; wire real engine**

`LibraryScreen` renders folders→items (LazyColumn). `PlayerScreen` owns a `Media3PlaybackEngine`, calls `setMediaUri(item.uri); play()`, hosts `PlayerView` via `AndroidView`, releases on dispose. Simple `when`-based navigation in `VideoPlayerApp` (no nav lib yet — YAGNI).

- [ ] **Step 4: [Android-verify] Full path on emulator: list → tap → video plays**

Install, push sample, open app, tap the item, confirm playback (screenshot shows video surface; logcat shows no crash; engine state `isPlaying=true`).
```bash
./gradlew :app:installDebug
adb shell am start -n com.videoplayer.app/.MainActivity
# manual/UiAutomator tap; adb exec-out screencap -p > /tmp/playing.png
```

- [ ] **Step 5: Commit — Phase 0 complete**

```bash
git add -A && git commit -m "feat: library browser to player playback end-to-end (Phase 0 complete)"
git tag phase-0
```

---

## PHASE 1 — Lean MVP toward first F-Droid release (work-package roadmap)

Phase 1 spans several **independent subsystems**. Per the writing-plans scope rule, each is its own bite-sized plan written *when we reach it* (so its steps reference the real code Phase 0 produced, not guesses). Below is the sequenced roadmap each package will expand into. Order respects dependencies; `[TDD]` marks the logic that gets real red-green unit tests, `[Android-verify]` the integration/UI.

> **Execution note:** When we start each package, invoke `superpowers:writing-plans` to expand it into a bite-sized task file `docs/superpowers/plans/2026-..-phase1-<pkg>.md`, then execute via subagents. This keeps each plan accurate to the code that exists at that moment.

### P1.A — Player controls & overlay shell  *(depends: Phase 0)*
Custom Compose control overlay (not Media3's default UI): play/pause, seek bar with position/duration, auto-hide after 3s, single-tap toggle visibility, double-tap = play/pause or ±10s skip (configurable). Keyframe-aware seek via Media3 `setSeekParameters(CLOSEST_SYNC)`.
- [TDD] auto-hide timer logic, double-tap zone resolution (left/right/center → action), seek-target math.
- [Android-verify] overlay shows/hides, seek works on emulator.

### P1.B — Gesture system (the signature feel)  *(depends: P1.A)*
Full gesture set with live overlays: left-half vertical = brightness, right-half vertical = volume (to 200% via `loudnessEnhancer`), horizontal drag = seek (+/− indicator), **long-press = 2× speed while held, release = restore (LOCKED V1 must-have)**, pinch = zoom/pan + aspect cycle. Each gesture individually toggleable + sensitivity-tunable.
- [TDD] pure `GestureResolver`: (touch position, drag delta, screen size, sensitivity, enabled-flags) → `GestureAction`. Long-press hold/release state machine. Volume-boost curve. **Heavily unit-tested — this is core feel logic.**
- [Android-verify] gestures on emulator + manual feel check (user may want to tune; flag as a check-in point).

### P1.C — Smart memory / persistence  *(depends: Phase 0; should land early — many features write to it)*
Room DB + DataStore. Per-file: resume position, speed, audio track, subtitle track, zoom/aspect, orientation. Folder-level defaults. **Data model forward-compatible for V2 language-learning fields** (nullable columns reserved, documented; no V2 logic built — per spec).
- [TDD] DAO logic (Robolectric in-memory Room), resume-vs-restart threshold math, folder-default resolution precedence (file overrides folder overrides global), forward-compat schema test.
- [Android-verify] resume actually works: play, exit, reopen → resumes.
- **Decision flag:** resume threshold + "ask vs auto-resume" UX is a product micro-fork → confirm with user when we reach it.

### P1.D — Library browser polish  *(depends: 0.6, P1.C)*
Media-only folder tree, sort/filter (name/date/duration), search, thumbnails (Media3/Glide frame extraction), watch history + "continue watching" row, auto-advance to next file in folder.
- [TDD] sort comparators, filter predicates, search matching, "next file in folder" resolution.
- [Android-verify] thumbnails render, history populates.

### P1.E — Playback feature set  *(depends: P1.A)*
Speed 0.25–4× with **pitch correction** (Media3 `PlaybackParameters`), frame-by-frame step, A–B repeat, aspect ratios (Best Fit/Fill/16:9/4:3/Center/zoom), sleep timer (duration OR end-of-video), screen-orientation lock (per-file remembered, writes via P1.C), screen lock + **Kids Lock** (locks touch + hardware keys, corner-combo unlock).
- [TDD] A–B loop boundary logic, sleep-timer countdown + end-of-video trigger, aspect-ratio scale computations, Kids-Lock unlock-combo state machine.
- [Android-verify] each control works on emulator.

### P1.F — PiP + background audio  *(depends: P1.A, P1.E)*
`MediaSessionService` + `MediaSession` (Media3) for background audio with notification transport controls; Picture-in-Picture (`enterPictureInPictureMode`, gated to API 26+). Keep playing audio with screen off / app backgrounded.
- [TDD] notification action → command mapping; PiP-availability predicate (API + setting).
- [Android-verify] PiP + background audio on emulator; notification controls.
- Adds `POST_NOTIFICATIONS` (API 33+) permission — first new permission; note in manifest review.

### P1.G — Basic subtitles (SRT/VTT)  *(depends: P1.A, P1.C)*
Load SRT/VTT: embedded tracks + external pick + **auto-scan sibling same-name subtitle in folder**. Render via Media3 text output. Per-file/folder subtitle choice remembered (via P1.C). 50ms sync nudge. (ASS/SSA + OpenSubtitles favorite-language flow are **Phase 2**, not here — per master prompt.)
- [TDD] sibling-subtitle filename matcher (`movie.mkv` ↔ `movie.srt`/`movie.en.srt`), sync-offset application, SRT/VTT cue parsing if we parse (else rely on Media3).
- [Android-verify] external sub loads and displays in sync.

### P1.H — Theme & UX polish  *(depends: most UI packages)*
Material You dynamic color (done in 0.4, refine), true-black AMOLED, customizable control bar (basic add/remove buttons), accessibility pass (touch targets, system caption styles, contrast).
- [TDD] control-bar config serialization (which buttons, order).
- [Android-verify] theme switching, AMOLED true-black, a11y check.

### P1.Z — F-Droid release prep  *(depends: all Phase 1)*
GPLv3 `LICENSE` (added in 0.1) + per-file headers where appropriate, `fastlane/metadata/android` (title, short/full description, changelog, screenshots), reproducible-build sanity, F-Droid metadata yaml, signing config for release, no proprietary deps audit (F-Droid requires fully FOSS dependency graph — **audit Media3 + all deps are FOSS-clean; flag any that aren't**).
- [Android-verify] `assembleRelease` builds; metadata validates; dependency licenses audited.
- **Decision flag:** F-Droid submission itself (outward-facing) → stop and confirm with user before submitting.

---

## Self-Review (against MASTER_PROMPT.md)

- **§1 Platform/engine (Kotlin+Compose, PlaybackEngine, Media3 default, mpv later):** Phase 0 Tasks 0.3–0.5 establish the interface + Media3 impl; libmpv explicitly deferred to Phase 2 per spec. ✅
- **§1 UI-agnostic core for KMP:** Global Constraints + `:core:*` pure modules (0.2, 0.3, 0.6). ✅
- **§2 Core playback (codecs, HW/SW, seek, speed):** basic playback 0.5/0.7; speed + keyframe seek + frame-step in P1.A/P1.E. HW/HW+/SW live toggle is partly engine-routing → full version arrives with mpv in Phase 2; basic Media3 decoder selection noted in P1.E. ✅ (with Phase 2 carry-over noted)
- **§3 Gestures incl. long-press 2×:** P1.B, long-press locked-in and TDD'd. ✅
- **§3 Kids Lock / screen lock / orientation lock:** P1.E. PiP/floating: P1.F. ✅
- **§4 Subtitles:** basic SRT/VTT + sibling scan + per-file memory in P1.G; ASS/SSA + OpenSubtitles favorite-language flow correctly deferred to Phase 2 per roadmap. ✅
- **§5 Audio (200% boost):** P1.B volume boost; equalizer/audio-sync/track-switch are Phase 2/3 per master roadmap. ✅
- **§6 Smart memory + library + auto-advance:** P1.C + P1.D; file management/trash/bookmarks/screenshot are Phase 3 per master roadmap (not Phase 1). ✅
- **§7 Network/cast:** Phase 2/3 — not in this plan. ✅
- **§8 Privacy:** Global Constraints (zero telemetry/min perms). Hidden folder = Phase 3. ✅
- **§9 UI principles (Material You, AMOLED, custom control bar):** 0.4 + P1.H. ✅
- **§10 Exclusions:** none of the excluded V1 items appear. ✅
- **§11 V2 forward-compat data model:** P1.C explicit forward-compat schema task. ✅
- **Placeholder scan:** Phase 0 tasks carry real code + exact commands. Phase 1 is intentionally roadmap-level (each expands to a bite-sized plan at execution time, per writing-plans scope rule) — this is a deliberate decomposition, not a placeholder.
- **Type consistency:** `PlaybackEngine`/`PlaybackState`/`PlayerStatus`/`EngineType`/`MediaItem`/`MediaFolder`/`MediaRepository`/`groupIntoFolders`/`formatDuration`/`exoStateToStatus` used consistently across tasks. ✅

## Open product/UX forks to confirm during execution (not blocking approval)
1. **Resume UX (P1.C):** auto-resume silently vs. a "Resume / Start over" prompt, and the threshold (e.g. ignore last <5s / >95%). 
2. **Double-tap default (P1.A):** play-pause vs. ±10s skip as the out-of-box default.
3. **Gesture feel tuning (P1.B):** sensitivity defaults likely need a hands-on pass with you on a real device.
4. **F-Droid submission (P1.Z):** outward-facing — will stop and confirm before submitting.
