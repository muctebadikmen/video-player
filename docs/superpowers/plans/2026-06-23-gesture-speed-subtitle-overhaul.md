# Hold-Speed Overhaul + Subtitle Styling/Move/Resize — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded 2× hold overlay with a subtle top-center badge + configurable one/two-finger hold speeds, and give subtitles research-backed styling (outline/shadow/box/system), a size + position control, and on-screen drag/pinch — all persisted in Settings.

**Architecture:** Pure JVM-tested helpers (speed selection, clamps, subtitle math, style→spec mapping) drive thin Compose/Media3 wiring. Five new DataStore prefs behind two seam interfaces (mirroring `GridSizePreferences`) are the single source of truth, read by both the player and the Settings UI. One style/size/position model feeds both subtitle render paths (Media3 `SubtitleView` for embedded, `CueOverlay` for external).

**Tech Stack:** Kotlin, Jetpack Compose, Media3/ExoPlayer (`CaptionStyleCompat`, `SubtitleView`), Jetpack DataStore, JUnit + Truth, Robolectric (for DataStore tests).

## Global Constraints

- JDK 21 for builds: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every Gradle call.
- `:core:*` modules must stay free of `android.*` / `androidx.compose.*` / `media3` imports. All new Android/Compose code lives in `:app`.
- License header on every new Kotlin file: `// SPDX-License-Identifier: GPL-3.0-or-later`.
- Tests use Truth: `import com.google.common.truth.Truth.assertThat`.
- Defaults: 1-finger hold `2.0×`, 2-finger hold `3.0×`; subtitle style `OUTLINE`, size fraction `0.0533`, bottom-padding fraction `0.08`.
- Ranges (clamp everywhere): hold speed `1.0..4.0`; subtitle size fraction `0.04..0.10`; subtitle bottom-padding fraction `0.02..0.50`.
- Surgical changes only; match existing style. Run `./gradlew :app:testDebugUnitTest` (or `./gradlew test`) for unit tests.

---

### Task 1: Pure gesture & subtitle math helpers

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/gestures/GestureMath.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/player/gestures/HoldSpeedAndSubtitleMathTest.kt` (create)

**Interfaces:**
- Produces:
  - `fun boostSpeedForPointers(pressedCount: Int, oneFinger: Float, twoFinger: Float): Float`
  - `fun formatSpeedLabel(speed: Float): String`
  - `fun clampHoldSpeed(speed: Float): Float`
  - `fun clampSubtitleSize(fraction: Float): Float`
  - `fun clampSubtitleBottomPadding(fraction: Float): Float`
  - `fun applySubtitleBottomPadding(current: Float, dragYpx: Float, heightPx: Float): Float`
  - `fun applySubtitleSize(current: Float, zoom: Float): Float`
  - constants `HOLD_SPEED_MIN/MAX`, `SUBTITLE_SIZE_MIN/MAX`, `SUBTITLE_POS_MIN/MAX`, defaults `DEFAULT_HOLD_SPEED_ONE/TWO`, `DEFAULT_SUBTITLE_SIZE_FRACTION`, `DEFAULT_SUBTITLE_BOTTOM_PADDING`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/videoplayer/app/player/gestures/HoldSpeedAndSubtitleMathTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldSpeedAndSubtitleMathTest {

    @Test fun `one pointer uses one-finger speed`() {
        assertThat(boostSpeedForPointers(1, 2f, 3f)).isEqualTo(2f)
    }

    @Test fun `two or more pointers use two-finger speed`() {
        assertThat(boostSpeedForPointers(2, 2f, 3f)).isEqualTo(3f)
        assertThat(boostSpeedForPointers(3, 2f, 3f)).isEqualTo(3f)
    }

    @Test fun `zero pointers falls back to one-finger speed`() {
        assertThat(boostSpeedForPointers(0, 2f, 3f)).isEqualTo(2f)
    }

    @Test fun `format whole speed drops decimal`() {
        assertThat(formatSpeedLabel(2f)).isEqualTo("2×")
        assertThat(formatSpeedLabel(3f)).isEqualTo("3×")
    }

    @Test fun `format fractional speed keeps one decimal`() {
        assertThat(formatSpeedLabel(2.5f)).isEqualTo("2.5×")
        assertThat(formatSpeedLabel(2.55f)).isEqualTo("2.6×")
    }

    @Test fun `clampHoldSpeed bounds to 1 to 4`() {
        assertThat(clampHoldSpeed(0.2f)).isEqualTo(1f)
        assertThat(clampHoldSpeed(9f)).isEqualTo(4f)
        assertThat(clampHoldSpeed(2.5f)).isEqualTo(2.5f)
    }

    @Test fun `clampSubtitleSize bounds to 0_04 to 0_10`() {
        assertThat(clampSubtitleSize(0.01f)).isEqualTo(0.04f)
        assertThat(clampSubtitleSize(0.5f)).isEqualTo(0.10f)
        assertThat(clampSubtitleSize(0.06f)).isEqualTo(0.06f)
    }

    @Test fun `clampSubtitleBottomPadding bounds to 0_02 to 0_50`() {
        assertThat(clampSubtitleBottomPadding(0f)).isEqualTo(0.02f)
        assertThat(clampSubtitleBottomPadding(0.9f)).isEqualTo(0.50f)
        assertThat(clampSubtitleBottomPadding(0.2f)).isEqualTo(0.2f)
    }

    @Test fun `dragging up increases bottom padding (moves subtitle up)`() {
        // dragYpx negative = upward; height 1000px, drag up 100px => +0.1
        val next = applySubtitleBottomPadding(0.1f, -100f, 1000f)
        assertThat(next).isWithin(1e-4f).of(0.2f)
    }

    @Test fun `dragging down decreases bottom padding and clamps`() {
        val next = applySubtitleBottomPadding(0.1f, 1000f, 1000f)
        assertThat(next).isEqualTo(0.02f) // clamped floor
    }

    @Test fun `applySubtitleBottomPadding ignores zero height`() {
        assertThat(applySubtitleBottomPadding(0.1f, -100f, 0f)).isEqualTo(0.1f)
    }

    @Test fun `pinch zoom scales size and clamps`() {
        assertThat(applySubtitleSize(0.05f, 1.2f)).isWithin(1e-4f).of(0.06f)
        assertThat(applySubtitleSize(0.05f, 10f)).isEqualTo(0.10f) // clamp ceiling
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.gestures.HoldSpeedAndSubtitleMathTest"`
Expected: FAIL (unresolved references — functions not defined).

- [ ] **Step 3: Write minimal implementation**

Append to `app/src/main/kotlin/com/videoplayer/app/player/gestures/GestureMath.kt` (add `import kotlin.math.roundToInt` at top with the other imports — there are none yet, so add an import line after the `package` line):

```kotlin
// --- Hold-to-speed (configurable one/two-finger) ---

const val HOLD_SPEED_MIN = 1.0f
const val HOLD_SPEED_MAX = 4.0f
const val DEFAULT_HOLD_SPEED_ONE = 2.0f
const val DEFAULT_HOLD_SPEED_TWO = 3.0f

/** Boost speed for the number of fingers currently held (>=2 fingers → two-finger speed). */
fun boostSpeedForPointers(pressedCount: Int, oneFinger: Float, twoFinger: Float): Float =
    if (pressedCount >= 2) twoFinger else oneFinger

/** Compact label for the speed badge: "2×", "2.5×" (one decimal, trailing .0 dropped). */
fun formatSpeedLabel(speed: Float): String {
    val rounded = (speed * 10f).roundToInt() / 10f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return "$text×"
}

fun clampHoldSpeed(speed: Float): Float = speed.coerceIn(HOLD_SPEED_MIN, HOLD_SPEED_MAX)

// --- Subtitle size & position (fractions of player height) ---

const val SUBTITLE_SIZE_MIN = 0.04f
const val SUBTITLE_SIZE_MAX = 0.10f
const val DEFAULT_SUBTITLE_SIZE_FRACTION = 0.0533f

const val SUBTITLE_POS_MIN = 0.02f
const val SUBTITLE_POS_MAX = 0.50f
const val DEFAULT_SUBTITLE_BOTTOM_PADDING = 0.08f

fun clampSubtitleSize(fraction: Float): Float = fraction.coerceIn(SUBTITLE_SIZE_MIN, SUBTITLE_SIZE_MAX)

fun clampSubtitleBottomPadding(fraction: Float): Float =
    fraction.coerceIn(SUBTITLE_POS_MIN, SUBTITLE_POS_MAX)

/** New bottom-padding fraction after a vertical drag; dragging **up** (negative dy) raises it. */
fun applySubtitleBottomPadding(current: Float, dragYpx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return clampSubtitleBottomPadding(current - dragYpx / heightPx)
}

/** New size fraction after a pinch; [zoom] > 1 grows the text. */
fun applySubtitleSize(current: Float, zoom: Float): Float = clampSubtitleSize(current * zoom)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.gestures.HoldSpeedAndSubtitleMathTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/gestures/GestureMath.kt app/src/test/kotlin/com/videoplayer/app/player/gestures/HoldSpeedAndSubtitleMathTest.kt
git commit -m "feat(player): pure helpers for configurable hold speed + subtitle size/position math"
```

---

### Task 2: Subtitle style model + spec mapping

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyle.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyleTest.kt` (create)

**Interfaces:**
- Produces:
  - `enum class SubtitleStyle { OUTLINE, DROP_SHADOW, BACKGROUND_BOX, SYSTEM }`
  - `enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }`
  - `data class SubtitleStyleSpec(val textColor: Long, val edge: SubtitleEdge, val edgeColor: Long, val backgroundColor: Long)`
  - `fun subtitleStyleSpec(style: SubtitleStyle): SubtitleStyleSpec`
  - `fun subtitleStyleFromName(name: String?): SubtitleStyle` (safe parse → OUTLINE default)

ARGB constants are plain `Long` (no `android.graphics.Color`) so this file stays unit-testable on the JVM.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyleTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleStyleTest {

    @Test fun `outline is white text with black outline and no background`() {
        val s = subtitleStyleSpec(SubtitleStyle.OUTLINE)
        assertThat(s.textColor).isEqualTo(0xFFFFFFFF)
        assertThat(s.edge).isEqualTo(SubtitleEdge.OUTLINE)
        assertThat(s.edgeColor).isEqualTo(0xFF000000)
        assertThat(s.backgroundColor).isEqualTo(0x00000000)
    }

    @Test fun `drop shadow is white text with shadow edge and no background`() {
        val s = subtitleStyleSpec(SubtitleStyle.DROP_SHADOW)
        assertThat(s.edge).isEqualTo(SubtitleEdge.DROP_SHADOW)
        assertThat(s.backgroundColor).isEqualTo(0x00000000)
    }

    @Test fun `background box is white text on translucent black with no edge`() {
        val s = subtitleStyleSpec(SubtitleStyle.BACKGROUND_BOX)
        assertThat(s.edge).isEqualTo(SubtitleEdge.NONE)
        assertThat(s.backgroundColor).isEqualTo(0xA6000000) // ~65% black
    }

    @Test fun `system falls back to outline spec for the custom overlay path`() {
        assertThat(subtitleStyleSpec(SubtitleStyle.SYSTEM)).isEqualTo(subtitleStyleSpec(SubtitleStyle.OUTLINE))
    }

    @Test fun `parse name is safe and defaults to outline`() {
        assertThat(subtitleStyleFromName("DROP_SHADOW")).isEqualTo(SubtitleStyle.DROP_SHADOW)
        assertThat(subtitleStyleFromName("bogus")).isEqualTo(SubtitleStyle.OUTLINE)
        assertThat(subtitleStyleFromName(null)).isEqualTo(SubtitleStyle.OUTLINE)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.subtitle.SubtitleStyleTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyle.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

/** User-selectable subtitle look. SYSTEM defers to OS caption settings (embedded path only). */
enum class SubtitleStyle { OUTLINE, DROP_SHADOW, BACKGROUND_BOX, SYSTEM }

/** Edge treatment, mapped per render path (Compose stroke/shadow or CaptionStyleCompat edge). */
enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }

/** Framework-agnostic style description (ARGB as Long) shared by both subtitle render paths. */
data class SubtitleStyleSpec(
    val textColor: Long,
    val edge: SubtitleEdge,
    val edgeColor: Long,
    val backgroundColor: Long,
)

private const val WHITE = 0xFFFFFFFF
private const val BLACK = 0xFF000000
private const val TRANSPARENT = 0x00000000
private const val BLACK_65 = 0xA6000000 // ~65% opaque black box

/** Concrete spec for a style. SYSTEM reuses OUTLINE for the custom (external) overlay. */
fun subtitleStyleSpec(style: SubtitleStyle): SubtitleStyleSpec = when (style) {
    SubtitleStyle.OUTLINE, SubtitleStyle.SYSTEM ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.OUTLINE, BLACK, TRANSPARENT)
    SubtitleStyle.DROP_SHADOW ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.DROP_SHADOW, BLACK, TRANSPARENT)
    SubtitleStyle.BACKGROUND_BOX ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.NONE, TRANSPARENT, BLACK_65)
}

/** Safe parse of a persisted style name; anything unknown → OUTLINE. */
fun subtitleStyleFromName(name: String?): SubtitleStyle =
    SubtitleStyle.entries.firstOrNull { it.name == name } ?: SubtitleStyle.OUTLINE
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.player.subtitle.SubtitleStyleTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyle.kt app/src/test/kotlin/com/videoplayer/app/player/subtitle/SubtitleStyleTest.kt
git commit -m "feat(subtitle): style model + framework-agnostic spec mapping"
```

---

### Task 3: Preferences — seam interfaces + SettingsRepository + round-trip test

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackGesturePreferences.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/SubtitlePreferences.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/memory/SettingsRepositoryPrefsTest.kt` (create)

**Interfaces:**
- Consumes (Task 1): `clampHoldSpeed`, `clampSubtitleSize`, `clampSubtitleBottomPadding`, `DEFAULT_*`. (Task 2): `SubtitleStyle`, `subtitleStyleFromName`.
- Produces:
  - `interface PlaybackGesturePreferences { val holdSpeedOneFinger: Flow<Float>; val holdSpeedTwoFinger: Flow<Float>; suspend fun setHoldSpeedOneFinger(s: Float); suspend fun setHoldSpeedTwoFinger(s: Float) }`
  - `interface SubtitlePreferences { val subtitleStyle: Flow<SubtitleStyle>; val subtitleSizeFraction: Flow<Float>; val subtitleBottomPaddingFraction: Flow<Float>; suspend fun setSubtitleStyle(style: SubtitleStyle); suspend fun setSubtitleSizeFraction(f: Float); suspend fun setSubtitleBottomPaddingFraction(f: Float) }`
  - `SettingsRepository` implements both (in addition to `GridSizePreferences`).

- [ ] **Step 1: Write the seam interfaces**

Create `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackGesturePreferences.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import kotlinx.coroutines.flow.Flow

/** Configurable hold-to-speed values, separable for testing (mirrors GridSizePreferences). */
interface PlaybackGesturePreferences {
    val holdSpeedOneFinger: Flow<Float>
    val holdSpeedTwoFinger: Flow<Float>
    suspend fun setHoldSpeedOneFinger(speed: Float)
    suspend fun setHoldSpeedTwoFinger(speed: Float)
}
```

Create `app/src/main/kotlin/com/videoplayer/app/data/memory/SubtitlePreferences.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import com.videoplayer.app.player.subtitle.SubtitleStyle
import kotlinx.coroutines.flow.Flow

/** Global subtitle appearance prefs, shared by both render paths and the Settings UI. */
interface SubtitlePreferences {
    val subtitleStyle: Flow<SubtitleStyle>
    val subtitleSizeFraction: Flow<Float>
    val subtitleBottomPaddingFraction: Flow<Float>
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setSubtitleSizeFraction(fraction: Float)
    suspend fun setSubtitleBottomPaddingFraction(fraction: Float)
}
```

- [ ] **Step 2: Write the failing round-trip test**

Create `app/src/test/kotlin/com/videoplayer/app/data/memory/SettingsRepositoryPrefsTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.player.subtitle.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryPrefsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun repo(): SettingsRepository {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb") }
        return SettingsRepository(ds)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `defaults match spec`() = runTest {
        val r = repo()
        assertThat(r.holdSpeedOneFinger.first()).isEqualTo(2.0f)
        assertThat(r.holdSpeedTwoFinger.first()).isEqualTo(3.0f)
        assertThat(r.subtitleStyle.first()).isEqualTo(SubtitleStyle.OUTLINE)
        assertThat(r.subtitleSizeFraction.first()).isEqualTo(0.0533f)
        assertThat(r.subtitleBottomPaddingFraction.first()).isEqualTo(0.08f)
    }

    @Test fun `setters round-trip and clamp`() = runTest {
        val r = repo()
        r.setHoldSpeedOneFinger(99f)   // clamps to 4
        r.setHoldSpeedTwoFinger(2.5f)
        r.setSubtitleStyle(SubtitleStyle.BACKGROUND_BOX)
        r.setSubtitleSizeFraction(0.5f)            // clamps to 0.10
        r.setSubtitleBottomPaddingFraction(0f)     // clamps to 0.02
        assertThat(r.holdSpeedOneFinger.first()).isEqualTo(4f)
        assertThat(r.holdSpeedTwoFinger.first()).isEqualTo(2.5f)
        assertThat(r.subtitleStyle.first()).isEqualTo(SubtitleStyle.BACKGROUND_BOX)
        assertThat(r.subtitleSizeFraction.first()).isEqualTo(0.10f)
        assertThat(r.subtitleBottomPaddingFraction.first()).isEqualTo(0.02f)
    }
}
```

NOTE: If `androidx.test.ext.junit.runners.AndroidJUnit4` / Robolectric is not already a test dependency, follow the existing `PlayerViewModelTest` pattern instead (it uses `@RunWith(RobolectricTestRunner::class)` + `ApplicationProvider`). Check `PlayerViewModelTest.kt` imports first and match them. Do NOT add new dependencies — reuse whatever that test already uses.

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.SettingsRepositoryPrefsTest"`
Expected: FAIL (SettingsRepository has no such members).

- [ ] **Step 4: Implement in SettingsRepository**

Modify `app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt`:

1. Add imports near the existing ones:
```kotlin
import androidx.datastore.preferences.core.stringPreferencesKey
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE
import com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_TWO
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_BOTTOM_PADDING
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION
import com.videoplayer.app.player.gestures.clampHoldSpeed
import com.videoplayer.app.player.gestures.clampSubtitleBottomPadding
import com.videoplayer.app.player.gestures.clampSubtitleSize
import com.videoplayer.app.player.subtitle.SubtitleStyle
import com.videoplayer.app.player.subtitle.subtitleStyleFromName
```

2. Change the class declaration to add the two interfaces:
```kotlin
class SettingsRepository(private val dataStore: DataStore<Preferences>) :
    GridSizePreferences, PlaybackGesturePreferences, SubtitlePreferences {
```

3. Add keys inside `object Keys`:
```kotlin
        val HOLD_SPEED_ONE = floatPreferencesKey("hold_speed_one_finger")
        val HOLD_SPEED_TWO = floatPreferencesKey("hold_speed_two_finger")
        val SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val SUBTITLE_SIZE = floatPreferencesKey("subtitle_size_fraction")
        val SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding_fraction")
```

4. Add members before the closing brace:
```kotlin
    override val holdSpeedOneFinger: Flow<Float> =
        dataStore.data.map { it[Keys.HOLD_SPEED_ONE] ?: DEFAULT_HOLD_SPEED_ONE }
    override val holdSpeedTwoFinger: Flow<Float> =
        dataStore.data.map { it[Keys.HOLD_SPEED_TWO] ?: DEFAULT_HOLD_SPEED_TWO }

    override suspend fun setHoldSpeedOneFinger(speed: Float) {
        dataStore.edit { it[Keys.HOLD_SPEED_ONE] = clampHoldSpeed(speed) }
    }
    override suspend fun setHoldSpeedTwoFinger(speed: Float) {
        dataStore.edit { it[Keys.HOLD_SPEED_TWO] = clampHoldSpeed(speed) }
    }

    override val subtitleStyle: Flow<SubtitleStyle> =
        dataStore.data.map { subtitleStyleFromName(it[Keys.SUBTITLE_STYLE]) }
    override val subtitleSizeFraction: Flow<Float> =
        dataStore.data.map { it[Keys.SUBTITLE_SIZE] ?: DEFAULT_SUBTITLE_SIZE_FRACTION }
    override val subtitleBottomPaddingFraction: Flow<Float> =
        dataStore.data.map { it[Keys.SUBTITLE_BOTTOM_PADDING] ?: DEFAULT_SUBTITLE_BOTTOM_PADDING }

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        dataStore.edit { it[Keys.SUBTITLE_STYLE] = style.name }
    }
    override suspend fun setSubtitleSizeFraction(fraction: Float) {
        dataStore.edit { it[Keys.SUBTITLE_SIZE] = clampSubtitleSize(fraction) }
    }
    override suspend fun setSubtitleBottomPaddingFraction(fraction: Float) {
        dataStore.edit { it[Keys.SUBTITLE_BOTTOM_PADDING] = clampSubtitleBottomPadding(fraction) }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.SettingsRepositoryPrefsTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackGesturePreferences.kt app/src/main/kotlin/com/videoplayer/app/data/memory/SubtitlePreferences.kt app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt app/src/test/kotlin/com/videoplayer/app/data/memory/SettingsRepositoryPrefsTest.kt
git commit -m "feat(settings): persist hold speeds + subtitle style/size/position prefs behind seams"
```

---

### Task 4: SpeedBadge + one/two-finger hold gesture rewrite

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/SpeedBadge.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (gesture block ~577-590; overlay block ~769-772; state decls ~186-190; prefs reads near the existing `settingsRepo` usage ~68)

**Interfaces:**
- Consumes (Task 1): `boostSpeedForPointers`, `formatSpeedLabel`. (Task 3): `settingsRepo.holdSpeedOneFinger`, `settingsRepo.holdSpeedTwoFinger`.
- Produces: visual behavior only (no new public API). New local state `speedBoostLabel`.

**Verification for this task is build + emulator** (Compose pointer plumbing isn't unit-tested; the speed-selection logic is already covered by Task 1).

- [ ] **Step 1: Create the SpeedBadge composable**

Create `app/src/main/kotlin/com/videoplayer/app/player/SpeedBadge.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Subtle top-center speed indicator shown while a hold-to-speed gesture is active.
 * Deliberately small and translucent so it does not interrupt the viewing experience.
 */
@Composable
fun SpeedBadge(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = "▶▶ $label",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
```

- [ ] **Step 2: Add state + read the prefs in PlayerScreen**

In `PlayerScreen.kt`, next to the existing gesture state (`var speedBoostActive by remember { mutableStateOf(false) }`, ~line 190) add:
```kotlin
        var speedBoostLabel by remember { mutableStateOf("") }
```

Near the existing `settingsRepo` reads (where `backgroundPlaybackEnabled` is collected, ~line 68), add reads + `rememberUpdatedState` so the gesture lambda always sees current values without restarting `pointerInput`:
```kotlin
        val holdSpeedOne by settingsRepo.holdSpeedOneFinger.collectAsStateWithLifecycle(initialValue = DEFAULT_HOLD_SPEED_ONE)
        val holdSpeedTwo by settingsRepo.holdSpeedTwoFinger.collectAsStateWithLifecycle(initialValue = DEFAULT_HOLD_SPEED_TWO)
        val holdOneState = rememberUpdatedState(holdSpeedOne)
        val holdTwoState = rememberUpdatedState(holdSpeedTwo)
```
Add imports as needed: `androidx.compose.runtime.rememberUpdatedState`, and `com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE` / `DEFAULT_HOLD_SPEED_TWO`, `boostSpeedForPointers`, `formatSpeedLabel`. (`collectAsStateWithLifecycle` is already imported — it's used for `backgroundPlaybackEnabled`.)

- [ ] **Step 3: Rewrite the long-press gesture block**

Replace the `.pointerInput(locked) { ... }` block at `PlayerScreen.kt:577-590` with:
```kotlin
            .pointerInput(locked) {
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (awaitLongPressOrCancellation(down.id) != null) {
                        val previousSpeed = engine.state.value.speed
                        var pressed = currentEvent.changes.count { it.pressed }.coerceAtLeast(1)
                        var applied = boostSpeedForPointers(pressed, holdOneState.value, holdTwoState.value)
                        engine.setSpeed(applied)
                        speedBoostLabel = formatSpeedLabel(applied)
                        speedBoostActive = true
                        // Track finger-count changes live until every pointer lifts.
                        while (true) {
                            val event = awaitPointerEvent()
                            pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            val next = boostSpeedForPointers(pressed, holdOneState.value, holdTwoState.value)
                            if (next != applied) {
                                applied = next
                                engine.setSpeed(applied)
                                speedBoostLabel = formatSpeedLabel(applied)
                            }
                        }
                        engine.setSpeed(previousSpeed)
                        speedBoostActive = false
                    }
                }
            },
```
Add import `androidx.compose.ui.input.pointer.AwaitPointerEventScope` is NOT needed; `currentEvent` and `awaitPointerEvent()` are members of the gesture scope already in use. If `waitForUpOrCancellation` is now unused elsewhere, remove its import (only if our change orphaned it — grep first).

NOTE on the boost saver: the existing `latestBoost`/`saveNow()` logic (~lines 454-466) resets saved speed to `1f` while boost is active so the boost speed isn't persisted. Keep that behavior — it still works since `speedBoostActive` still toggles. Verify it compiles unchanged.

- [ ] **Step 4: Swap the centered 2× overlay for the top badge**

Replace the overlay block at `PlayerScreen.kt:769-772`:
```kotlin
        if (!inPip) {
            gestureLabel?.let { GestureOverlay(label = it) }
            if (speedBoostActive) GestureOverlay(label = "${BOOST_SPEED.toInt()}×")
        }
```
with:
```kotlin
        if (!inPip) {
            gestureLabel?.let { GestureOverlay(label = it) }
            if (speedBoostActive) SpeedBadge(label = speedBoostLabel)
        }
```
`BOOST_SPEED` may now be unused — grep for other usages; if none, remove the constant from `GestureMath.kt` (Task 1 left it untouched). If anything else references it, leave it.

- [ ] **Step 5: Build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/SpeedBadge.kt app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt app/src/main/kotlin/com/videoplayer/app/player/gestures/GestureMath.kt
git commit -m "feat(player): subtle top badge + configurable one/two-finger hold speeds"
```

---

### Task 5: Subtitle styling on both render paths

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/CaptionStyleMapper.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/CueOverlay.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (AndroidView `update` ~601-627; CueOverlay call ~774-782; new subtitle prefs reads + local state)

**Interfaces:**
- Consumes (Task 2): `SubtitleStyle`, `SubtitleEdge`, `SubtitleStyleSpec`, `subtitleStyleSpec`. (Task 3): `settingsRepo.subtitleStyle/subtitleSizeFraction/subtitleBottomPaddingFraction`.
- Produces:
  - `fun captionStyleCompatFor(spec: SubtitleStyleSpec): CaptionStyleCompat`
  - `CueOverlay(text, style, sizeFraction, bottomPaddingFraction, modifier)` new signature.

- [ ] **Step 1: CaptionStyleCompat mapper (embedded path)**

Create `app/src/main/kotlin/com/videoplayer/app/player/subtitle/CaptionStyleMapper.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat

/** Maps a framework-agnostic [SubtitleStyleSpec] to Media3's SubtitleView caption style. */
fun captionStyleCompatFor(spec: SubtitleStyleSpec): CaptionStyleCompat {
    val edgeType = when (spec.edge) {
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
    return CaptionStyleCompat(
        spec.textColor.toInt(),
        spec.backgroundColor.toInt(),
        Color.TRANSPARENT,
        edgeType,
        spec.edgeColor.toInt(),
        null,
    )
}
```

- [ ] **Step 2: Restyle CueOverlay (external path)**

Replace the body of `app/src/main/kotlin/com/videoplayer/app/player/subtitle/CueOverlay.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text

/**
 * Renders the active cue for the *external/sibling* subtitle path with the user's chosen
 * style/size/position. Embedded tracks are styled separately on Media3's SubtitleView, but
 * both paths read the same fractions so they look identical.
 */
@Composable
fun CueOverlay(
    text: String?,
    style: SubtitleStyle,
    sizeFraction: Float,
    bottomPaddingFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
    val spec = subtitleStyleSpec(style)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val heightPx = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fontSp = with(density) { (sizeFraction * heightPx).toSp() }
        val bottomDp = with(density) { (bottomPaddingFraction * heightPx).toDp() }
        val strokeWidthPx = (sizeFraction * heightPx) * 0.10f
        val textColor = Color(spec.textColor.toInt())
        val edgeColor = Color(spec.edgeColor.toInt())

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = bottomDp),
            contentAlignment = Alignment.Center,
        ) {
            when (spec.edge) {
                SubtitleEdge.OUTLINE -> {
                    // Stroke layer behind a solid fill layer = crisp outline.
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        style = TextStyle(color = edgeColor, drawStyle = Stroke(width = strokeWidthPx)),
                    )
                    Text(text = text, textAlign = TextAlign.Center, fontSize = fontSp, color = textColor)
                }
                SubtitleEdge.DROP_SHADOW -> {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        color = textColor,
                        style = TextStyle(
                            shadow = Shadow(
                                color = edgeColor,
                                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                                blurRadius = strokeWidthPx * 1.5f,
                            ),
                        ),
                    )
                }
                SubtitleEdge.NONE -> {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        fontSize = fontSp,
                        color = textColor,
                        modifier = Modifier
                            .background(Color(spec.backgroundColor.toInt()), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
```

(If the unused `18.sp` import lints, drop the `sp` import — it's used via `toSp()` so keep `androidx.compose.ui.unit.sp` only if referenced; remove if not. Build will tell you.)

- [ ] **Step 3: Wire subtitle prefs + apply to both paths in PlayerScreen**

In `PlayerScreen.kt`, near the other settings reads (Task 4 added hold speeds there), add:
```kotlin
        val subtitleStylePref by settingsRepo.subtitleStyle.collectAsStateWithLifecycle(initialValue = SubtitleStyle.OUTLINE)
        var subtitleSizeFraction by remember { mutableStateOf(DEFAULT_SUBTITLE_SIZE_FRACTION) }
        var subtitleBottomPadding by remember { mutableStateOf(DEFAULT_SUBTITLE_BOTTOM_PADDING) }
        // Seed the local (drag-mutable) state from prefs once they emit.
        val sizePref by settingsRepo.subtitleSizeFraction.collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_SIZE_FRACTION)
        val posPref by settingsRepo.subtitleBottomPaddingFraction.collectAsStateWithLifecycle(initialValue = DEFAULT_SUBTITLE_BOTTOM_PADDING)
        LaunchedEffect(sizePref) { subtitleSizeFraction = sizePref }
        LaunchedEffect(posPref) { subtitleBottomPadding = posPref }
```
Imports: `com.videoplayer.app.player.subtitle.SubtitleStyle`, `com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION`, `DEFAULT_SUBTITLE_BOTTOM_PADDING`, `com.videoplayer.app.player.subtitle.captionStyleCompatFor`, `com.videoplayer.app.player.subtitle.subtitleStyleSpec`. (`LaunchedEffect`, `remember`, `mutableStateOf`, `getValue`, `setValue` are already imported.)

In the `AndroidView` `update` lambda (after the aspect-ratio `when`, before the closing brace, ~line 626) add embedded styling:
```kotlin
                view.subtitleView?.let { sv ->
                    if (subtitleStylePref == SubtitleStyle.SYSTEM) {
                        sv.setApplyEmbeddedStyles(true)
                        sv.setUserDefaultStyle()
                        sv.setUserDefaultTextSize()
                    } else {
                        sv.setApplyEmbeddedStyles(false)
                        sv.setStyle(captionStyleCompatFor(subtitleStyleSpec(subtitleStylePref)))
                        sv.setFractionalTextSize(subtitleSizeFraction)
                    }
                    sv.setBottomPaddingFraction(subtitleBottomPadding)
                }
```

Replace the `CueOverlay(...)` call at `PlayerScreen.kt:774-782` with the new signature (the overlay now owns its own bottom padding, so drop the fixed `bottom = 72.dp`):
```kotlin
        if (!inPip) {
            CueOverlay(
                text = activeCueText(subtitleCues, state.positionMs, subtitleOffsetMs, subtitleRate.toDouble()),
                style = subtitleStylePref,
                sizeFraction = subtitleSizeFraction,
                bottomPaddingFraction = subtitleBottomPadding,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
```

- [ ] **Step 4: Build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Fix any unused-import warnings our edits orphaned.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/subtitle/CaptionStyleMapper.kt app/src/main/kotlin/com/videoplayer/app/player/subtitle/CueOverlay.kt app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(subtitle): apply chosen style/size/position to embedded + external paths"
```

---

### Task 6: On-screen subtitle drag (move) + pinch (resize)

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (add a gesture layer; persist on end)

**Interfaces:**
- Consumes (Task 1): `applySubtitleBottomPadding`, `applySubtitleSize`. (Task 3 setters): `settingsRepo.setSubtitleSizeFraction`, `setSubtitleBottomPaddingFraction`. Local state from Task 5: `subtitleSizeFraction`, `subtitleBottomPadding`.
- Produces: visual behavior only.

**Verification: build + emulator.**

- [ ] **Step 1: Add a coroutine scope for persistence (if not already present)**

Near the top of `PlayerScreen` content, ensure there is a `val scope = rememberCoroutineScope()` (grep first; if one exists reuse it). Import `androidx.compose.runtime.rememberCoroutineScope` if needed.

- [ ] **Step 2: Add the subtitle adjust layer**

Add this Box INSIDE the root player Box, AFTER the `CueOverlay(...)` block from Task 5 (so it sits above the subtitle), and only active when controls are visible so it never competes with the hold-to-speed gesture on the bare surface:
```kotlin
        if (!inPip && controlsVisible) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f) // lower half = subtitle adjust zone
            ) {
                val hPx = constraints.maxHeight.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // Vertical drag → move; persist on end.
                            detectVerticalDragGestures(
                                onDragEnd = { scope.launch { settingsRepo.setSubtitleBottomPaddingFraction(subtitleBottomPadding) } },
                            ) { change, dragAmount ->
                                change.consume()
                                subtitleBottomPadding = applySubtitleBottomPadding(subtitleBottomPadding, dragAmount, hPx)
                            }
                        }
                        .pointerInput(Unit) {
                            // Pinch → resize; persist on end.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        subtitleSizeFraction = applySubtitleSize(subtitleSizeFraction, zoom)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                scope.launch { settingsRepo.setSubtitleSizeFraction(subtitleSizeFraction) }
                            }
                        },
                )
            }
        }
```
Imports: `androidx.compose.foundation.gestures.detectVerticalDragGestures` (already imported), `androidx.compose.foundation.gestures.calculateZoom`, `androidx.compose.foundation.layout.fillMaxHeight`, `androidx.compose.foundation.layout.BoxWithConstraints`, `kotlinx.coroutines.launch`.

NOTE: Two `pointerInput` modifiers on one node run independently; the drag handles single-finger vertical moves, the second handles multi-finger zoom. If they conflict in testing, prefer keeping the vertical-drag (move) layer and rely on the Settings size slider for resize — moving is the higher-value gesture. Document whichever you ship.

- [ ] **Step 3: Build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(subtitle): drag to move and pinch to resize when controls are visible"
```

---

### Task 7: Settings UI — gesture speeds + subtitle style/size/position + preview

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes (Task 3): repo flows + setters. (Task 2): `SubtitleStyle`. (Task 5): `CueOverlay` for the preview (optional) — or a simple inline sample.
- Produces: Settings UI; ViewModel flows/setters.

- [ ] **Step 1: Extend SettingsViewModel**

Add to `SettingsViewModel.kt` (after the `backgroundPlaybackEnabled` block):
```kotlin
    val holdSpeedOneFinger: StateFlow<Float> =
        repo.holdSpeedOneFinger.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_HOLD_SPEED_ONE)
    val holdSpeedTwoFinger: StateFlow<Float> =
        repo.holdSpeedTwoFinger.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_HOLD_SPEED_TWO)
    val subtitleStyle: StateFlow<SubtitleStyle> =
        repo.subtitleStyle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleStyle.OUTLINE)
    val subtitleSizeFraction: StateFlow<Float> =
        repo.subtitleSizeFraction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SUBTITLE_SIZE_FRACTION)
    val subtitleBottomPaddingFraction: StateFlow<Float> =
        repo.subtitleBottomPaddingFraction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SUBTITLE_BOTTOM_PADDING)

    fun setHoldSpeedOneFinger(v: Float) { viewModelScope.launch { repo.setHoldSpeedOneFinger(v) } }
    fun setHoldSpeedTwoFinger(v: Float) { viewModelScope.launch { repo.setHoldSpeedTwoFinger(v) } }
    fun setSubtitleStyle(s: SubtitleStyle) { viewModelScope.launch { repo.setSubtitleStyle(s) } }
    fun setSubtitleSize(v: Float) { viewModelScope.launch { repo.setSubtitleSizeFraction(v) } }
    fun setSubtitlePosition(v: Float) { viewModelScope.launch { repo.setSubtitleBottomPaddingFraction(v) } }
```
Imports: `com.videoplayer.app.player.gestures.DEFAULT_HOLD_SPEED_ONE`, `DEFAULT_HOLD_SPEED_TWO`, `DEFAULT_SUBTITLE_SIZE_FRACTION`, `DEFAULT_SUBTITLE_BOTTOM_PADDING`, `com.videoplayer.app.player.subtitle.SubtitleStyle`.

- [ ] **Step 2: Add the Settings sections**

In `SettingsScreen.kt`, collect the new flows at the top of `SettingsScreen`:
```kotlin
    val holdOne by vm.holdSpeedOneFinger.collectAsStateWithLifecycle()
    val holdTwo by vm.holdSpeedTwoFinger.collectAsStateWithLifecycle()
    val subStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val subSize by vm.subtitleSizeFraction.collectAsStateWithLifecycle()
    val subPos by vm.subtitleBottomPaddingFraction.collectAsStateWithLifecycle()
```

Add, after the background-playback row (before/around the `OpenSubtitlesSettings`):
```kotlin
            HorizontalDivider()
            SectionHeader("Playback gestures")
            SettingSliderRow(
                title = "Hold to speed up · 1 finger",
                valueLabel = "${formatSpeedLabel(holdOne)}",
                value = holdOne, valueRange = HOLD_SPEED_MIN..HOLD_SPEED_MAX, steps = 29,
                onValueChange = vm::setHoldSpeedOneFinger,
            )
            SettingSliderRow(
                title = "Hold to speed up · 2 fingers",
                valueLabel = "${formatSpeedLabel(holdTwo)}",
                value = holdTwo, valueRange = HOLD_SPEED_MIN..HOLD_SPEED_MAX, steps = 29,
                onValueChange = vm::setHoldSpeedTwoFinger,
            )
            HorizontalDivider()
            SectionHeader("Subtitles")
            SubtitleStylePicker(selected = subStyle, onSelect = vm::setSubtitleStyle)
            SettingSliderRow(
                title = "Subtitle size",
                valueLabel = "${(subSize / DEFAULT_SUBTITLE_SIZE_FRACTION * 100).toInt()}%",
                value = subSize, valueRange = SUBTITLE_SIZE_MIN..SUBTITLE_SIZE_MAX, steps = 0,
                onValueChange = vm::setSubtitleSize,
            )
            SettingSliderRow(
                title = "Subtitle position (height from bottom)",
                valueLabel = "${(subPos * 100).toInt()}%",
                value = subPos, valueRange = SUBTITLE_POS_MIN..SUBTITLE_POS_MAX, steps = 0,
                onValueChange = vm::setSubtitlePosition,
            )
            SubtitlePreview(style = subStyle, sizeFraction = subSize)
```

Add these private composables to `SettingsScreen.kt`:
```kotlin
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun SubtitleStylePicker(selected: SubtitleStyle, onSelect: (SubtitleStyle) -> Unit) {
    val options = listOf(
        SubtitleStyle.OUTLINE to "Outline",
        SubtitleStyle.DROP_SHADOW to "Drop shadow",
        SubtitleStyle.BACKGROUND_BOX to "Background box",
        SubtitleStyle.SYSTEM to "Follow system",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEach { (style, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(style) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected == style, onClick = { onSelect(style) })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SubtitlePreview(style: SubtitleStyle, sizeFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(96.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        CueOverlay(
            text = "Sample subtitle",
            style = style,
            sizeFraction = (sizeFraction * 1.6f), // scale up a touch for the small preview box
            bottomPaddingFraction = 0.1f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```
Imports to add: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.material3.RadioButton`, `androidx.compose.material3.Slider`, `com.videoplayer.app.player.subtitle.SubtitleStyle`, `com.videoplayer.app.player.subtitle.CueOverlay`, `com.videoplayer.app.player.gestures.*` (the constants + `formatSpeedLabel`).

- [ ] **Step 3: Build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/settings/SettingsViewModel.kt app/src/main/kotlin/com/videoplayer/app/settings/SettingsScreen.kt
git commit -m "feat(settings): UI for hold speeds + subtitle style/size/position with live preview"
```

---

### Task 8: Full verification + finish

**Files:** none (verification only).

- [ ] **Step 1: Full unit-test run**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`
Expected: BUILD SUCCESSFUL, all modules green.

- [ ] **Step 2: Full debug build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Emulator smoke test (AVD `kuran_test`)**

Boot the AVD, `./gradlew :app:installDebug`, open a video with subtitles, and verify:
- Long-press (1 finger) → playback speeds to 2× with a small top-center badge; release restores.
- Add a 2nd finger during the hold → 3× live; remove → back to 2×; lift all → restore.
- No large centered "2×" overlay remains.
- Settings → change 1-finger to 2.5×, 2-finger to 4× → behavior follows.
- Settings → cycle subtitle styles (Outline/Drop shadow/Background box/Follow system); preview + actual subtitles update; size + position sliders move both embedded and external subs.
- With controls visible, drag the subtitle up/down to move; pinch to resize; values persist after reopening the video.

Capture a screenshot or two with `adb` for evidence.

- [ ] **Step 4: Finish the branch**

Use superpowers:finishing-a-development-branch to present merge/PR options.

## Self-Review

- **Spec coverage:** Hold badge (T4) ✓; configurable 1/2-finger speeds (T1+T3+T4+T7) ✓; subtitle outline default + full preset set on both paths (T2+T5) ✓; size + position (T1+T3+T5+T7) ✓; drag/pinch (T6) ✓; Settings UI + preview (T7) ✓; persistence behind seams (T3) ✓; tests (T1–T3) + verification (T8) ✓.
- **Placeholder scan:** none — every code step has concrete code; the two "if it conflicts, fall back to sliders" notes are explicit ship-decisions, not deferrals.
- **Type consistency:** `SubtitleStyle`/`SubtitleEdge`/`SubtitleStyleSpec`/`subtitleStyleSpec`/`subtitleStyleFromName`/`captionStyleCompatFor` and the repo flow/setters names are used identically across T2/T3/T5/T7; `CueOverlay(text, style, sizeFraction, bottomPaddingFraction, modifier)` signature is consistent T5↔T6↔T7; helper names match T1↔T3↔T4↔T6.
