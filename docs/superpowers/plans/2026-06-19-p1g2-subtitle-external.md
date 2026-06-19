# P1.G-2 — External + Sibling Subtitles (custom render) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the user load an external SRT/VTT subtitle (manual SAF pick + best-effort sibling scan), render it through a custom Compose cue overlay synced to playback position, choose it from a CC dropdown menu, and nudge its timing by ±50ms — all on top of the already-tested pure core from G-1.

**Architecture:** A thin app-side data layer reads/parses subtitle files off the main thread (`SubtitleLoader`) and discovers same-folder siblings via MediaStore (`SiblingSubtitleScanner`, best-effort), reusing G-1's tested `parseSubtitles` / `findSiblingSubtitles`. `PlayerScreen` holds per-file subtitle state (re-keyed on `currentItem.uri`), drives a `CueOverlay` that shows `activeCueText(cues, positionMs, offsetMs)`, and exposes a CC menu (Off / siblings+externals / Load file… / ±50ms nudge) through `PlayerControls`. Only external/sibling subs are handled here; embedded Media3 tracks are G-3, per-file memory is G-4.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), AndroidX `ActivityResultContracts.OpenDocument`, MediaStore.Files, coroutines (Dispatchers.IO). Pure subtitle logic already in `:core:playback` (G-1).

## Global Constraints

- Build with JDK 21 — prefix every gradle call: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew ...`. Repo root: `/Users/mustafa/Desktop/Projects/mobil uygulama/video-player`.
- `:core:*` stays pure Kotlin — NO new core code in this sub-package (it only consumes G-1's `SubtitleCue`, `parseSubtitles`, `activeCueText`, `findSiblingSubtitles`, `SUBTITLE_NUDGE_MS`, `nudgeSubtitleOffset`). All new code lives in `:app`.
- **No new permissions.** SAF grants per-file URI access; sibling scan uses the existing `READ_MEDIA_VIDEO`/`READ_EXTERNAL_STORAGE`. Do NOT add storage permissions.
- **No new dependencies.** No `material-icons-extended` — use core `Icons.Filled.*` (e.g. `Icons.Filled.Check`) or text.
- Errors never crash: `SubtitleLoader` and `SiblingSubtitleScanner` catch all exceptions and return empty.
- minSdk 24, targetSdk/compileSdk 35.
- Must NOT regress P1.A–P1.F (controls, gestures, resume, locks, PiP, background audio, playlist/auto-advance, settings).
- Commit after every green step (green = `:app:assembleDebug` BUILD SUCCESSFUL + existing unit suites still pass).
- Spec: `docs/superpowers/specs/2026-06-19-p1g-subtitles-design.md`.

---

### Task G2.1: Subtitle data layer — model, loader, sibling scanner

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/SubtitleOption.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/SubtitleLoader.kt`
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/SiblingSubtitleScanner.kt`

**Interfaces — Produces:**
- `data class SubtitleOption(val uri: String, val label: String)`
- `object SubtitleLoader { suspend fun load(context: Context, uri: String): List<SubtitleCue> }`
- `object SiblingSubtitleScanner { suspend fun scan(context: Context, videoFolderName: String, videoFileName: String): List<SubtitleOption> }`

**Interfaces — Consumes (from G-1, `com.videoplayer.core.playback`):** `SubtitleCue`, `parseSubtitles(content: String): List<SubtitleCue>`, `findSiblingSubtitles(videoFileName: String, candidates: List<String>): List<String>`.

This task has no pure logic to unit-test (it consumes G-1's tested functions and otherwise does Android IO/MediaStore). It is **[Android-verify]**: the green gate is `:app:assembleDebug` succeeding and the existing unit suites staying green. The runtime behavior is verified in Task G2.3 (device smoke).

- [ ] **Step 1: Create `SubtitleOption.kt`**

```kotlin
package com.videoplayer.app.player.subtitle

/**
 * A selectable external or sibling subtitle file.
 * [uri] is the content/file URI string (the stable key); [label] is the menu text.
 */
data class SubtitleOption(val uri: String, val label: String)
```

- [ ] **Step 2: Create `SubtitleLoader.kt`**

```kotlin
package com.videoplayer.app.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import com.videoplayer.core.playback.SubtitleCue
import com.videoplayer.core.playback.parseSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a subtitle file via ContentResolver and parses it to cues using the tolerant
 * core parser (handles both SRT and VTT). Any failure (missing file, unreadable,
 * revoked permission) logs and returns an empty list — never throws.
 */
object SubtitleLoader {
    private const val TAG = "SubtitleLoader"

    suspend fun load(context: Context, uri: String): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(Uri.parse(uri))
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: return@withContext emptyList()
                parseSubtitles(text)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load subtitle: $uri", e)
                emptyList()
            }
        }
}
```

- [ ] **Step 3: Create `SiblingSubtitleScanner.kt`**

```kotlin
package com.videoplayer.app.player.subtitle

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.videoplayer.core.playback.findSiblingSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort discovery of same-folder SRT/VTT siblings for a video, via MediaStore.Files.
 * On scoped storage (API 29+) only MediaStore-indexed subtitle files are visible, so this may
 * return nothing even when a sibling exists on disk — the reliable path is the manual SAF pick.
 * Results are restricted to the video's own folder (by folder name) when the file's path is known.
 * Any failure returns an empty list.
 */
object SiblingSubtitleScanner {
    private const val TAG = "SiblingSubtitleScanner"

    @Suppress("DEPRECATION") // DATA column used only to compare folder names; tolerated as best-effort.
    suspend fun scan(
        context: Context,
        videoFolderName: String,
        videoFileName: String,
    ): List<SubtitleOption> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleOption>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
            )
            val selection =
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%.srt", "%.vtt")

            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                data class Row(val id: Long, val name: String, val folder: String?)
                val rows = mutableListOf<Row>()
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val data = if (dataCol >= 0) c.getString(dataCol) else null
                    val folder = data?.substringBeforeLast('/')?.substringAfterLast('/')
                    rows.add(Row(id, name, folder))
                }

                val matchingNames = findSiblingSubtitles(videoFileName, rows.map { it.name }).toSet()
                val seen = mutableSetOf<String>()
                for (row in rows) {
                    if (row.name !in matchingNames) continue
                    // When we know the folder, require it to match the video's folder.
                    if (row.folder != null && row.folder != videoFolderName) continue
                    val uri = ContentUris.withAppendedId(collection, row.id).toString()
                    if (seen.add(uri)) results.add(SubtitleOption(uri, row.name))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sibling subtitle scan failed", e)
        }
        results
    }
}
```

- [ ] **Step 4: Build to verify it compiles and nothing regressed**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :core:playback:test`
Expected: `BUILD SUCCESSFUL`; `:core:playback:test` still green (61 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/subtitle && git commit -m "feat(player): subtitle data layer — option model, file loader, sibling scanner"
```

---

### Task G2.2: Cue overlay, CC menu + nudge, and PlayerScreen wiring (SAF pick)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/subtitle/CueOverlay.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt` (add CC button + subtitle dropdown with nudge)
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (subtitle state, sibling-scan + cue-load effects, SAF launcher, CueOverlay, pass props to PlayerControls)

**Interfaces — Consumes:** `SubtitleOption`, `SubtitleLoader`, `SiblingSubtitleScanner` (G2.1); `SubtitleCue`, `activeCueText`, `SUBTITLE_NUDGE_MS`, `nudgeSubtitleOffset` (G-1 core); existing `PlayerControls(...)` signature, the root `Box` overlay layering, `currentItem` (`MediaItem` with `displayName`/`folderPath`/`uri`), `state.positionMs`, `inPip` (all per the codebase).
**Interfaces — Produces:** `@Composable fun CueOverlay(text: String?, modifier: Modifier = Modifier)`; an expanded `PlayerControls(...)` with six new subtitle params (below); subtitles selectable + rendered + nudgeable at runtime.

This task is **[Android-verify]** (Compose UI + Android IO; green gate = `:app:assembleDebug`). Runtime behavior verified in G2.3.

- [ ] **Step 1: Create `CueOverlay.kt`**

```kotlin
package com.videoplayer.app.player.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders the currently active subtitle cue text with a readable scrim. Shows nothing
 * when [text] is null/blank. The caller supplies the active cue via
 * activeCueText(cues, positionMs, offsetMs), so this composable stays presentation-only.
 */
@Composable
fun CueOverlay(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
```

- [ ] **Step 2: Add the CC button + subtitle dropdown (with nudge) to `PlayerControls.kt`**

Add these six params to the `PlayerControls` composable signature (place them after the existing `onCycleOrientation` / before `pipSupported`, keeping a trailing comma; order is not load-bearing but keep them grouped):

```kotlin
    subtitleOptions: List<SubtitleOption>,
    selectedSubtitleUri: String?,
    subtitleOffsetMs: Long,
    onSelectSubtitle: (String?) -> Unit,
    onLoadSubtitleFile: () -> Unit,
    onNudgeSubtitle: (Long) -> Unit,
```

Add these imports to `PlayerControls.kt` (alongside the existing ones):

```kotlin
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import com.videoplayer.app.player.subtitle.SubtitleOption
import com.videoplayer.core.playback.SUBTITLE_NUDGE_MS
```

In the **secondary control `Row`** (the one with `Arrangement.spacedBy(4.dp)` holding speed/frame-step/A-B/sleep/orientation), add a CC control. Place it right after the A–B repeat button (so it sits next to the playback toggles). Declare its expansion state next to the other menu states (e.g. alongside `sleepMenuExpanded`):

```kotlin
    var subtitleMenuExpanded by remember { mutableStateOf(false) }
```

And the CC control itself:

```kotlin
Box {
    TextButton(onClick = { subtitleMenuExpanded = true }) {
        Text(
            text = "CC",
            color = if (selectedSubtitleUri != null) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
    DropdownMenu(
        expanded = subtitleMenuExpanded,
        onDismissRequest = { subtitleMenuExpanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Off") },
            onClick = {
                onSelectSubtitle(null)
                subtitleMenuExpanded = false
            },
            trailingIcon = {
                if (selectedSubtitleUri == null) Icon(Icons.Filled.Check, contentDescription = null)
            },
        )
        subtitleOptions.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = {
                    onSelectSubtitle(option.uri)
                    subtitleMenuExpanded = false
                },
                trailingIcon = {
                    if (selectedSubtitleUri == option.uri) Icon(Icons.Filled.Check, contentDescription = null)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Load subtitle file…") },
            onClick = {
                onLoadSubtitleFile()
                subtitleMenuExpanded = false
            },
        )
        // Sync nudge — only meaningful while a custom subtitle is active. Items keep the
        // menu open so the user can tap repeatedly; the offset readout updates live.
        if (selectedSubtitleUri != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Sync −50ms (delay)") },
                onClick = { onNudgeSubtitle(-SUBTITLE_NUDGE_MS) },
            )
            DropdownMenuItem(
                text = { Text("Offset: ${subtitleOffsetMs} ms") },
                onClick = {},
                enabled = false,
            )
            DropdownMenuItem(
                text = { Text("Sync +50ms (earlier)") },
                onClick = { onNudgeSubtitle(SUBTITLE_NUDGE_MS) },
            )
        }
    }
}
```

> Note: confirm `Icon`, `DropdownMenu`, `DropdownMenuItem`, `MaterialTheme`, `TextButton`, `Text`, `mutableStateOf`/`remember` are already imported in `PlayerControls.kt` (they are used by the existing speed/sleep menus); only add the four new imports listed above.

- [ ] **Step 3: Wire subtitle state + effects + SAF + overlay into `PlayerScreen.kt`**

Add imports:

```kotlin
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.videoplayer.app.player.subtitle.CueOverlay
import com.videoplayer.app.player.subtitle.SiblingSubtitleScanner
import com.videoplayer.app.player.subtitle.SubtitleLoader
import com.videoplayer.app.player.subtitle.SubtitleOption
import com.videoplayer.core.playback.SubtitleCue
import com.videoplayer.core.playback.activeCueText
import com.videoplayer.core.playback.nudgeSubtitleOffset
```

> `rememberLauncherForActivityResult` + `ActivityResultContracts` are already used in PlayerScreen for `POST_NOTIFICATIONS` — reuse those imports if present and only add what's missing.

Add subtitle state near the other per-file state (the block with `aspectMode`, `locked`, `abLoop`, all keyed on `currentItem.uri`). Re-key all of these on `currentItem.uri` so a new file starts with no subtitle (G-4 will restore from memory):

```kotlin
var siblingSubtitles by remember(currentItem.uri) { mutableStateOf<List<SubtitleOption>>(emptyList()) }
var externalSubtitles by remember(currentItem.uri) { mutableStateOf<List<SubtitleOption>>(emptyList()) }
var selectedSubtitleUri by remember(currentItem.uri) { mutableStateOf<String?>(null) }
var subtitleOffsetMs by remember(currentItem.uri) { mutableStateOf(0L) }
var subtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
val subtitleOptions = remember(siblingSubtitles, externalSubtitles) {
    (siblingSubtitles + externalSubtitles).distinctBy { it.uri }
}
```

Add the SAF picker launcher (place with the other `rememberLauncherForActivityResult` declarations):

```kotlin
val subtitlePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri: Uri? ->
    if (uri != null) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = subtitleDisplayName(context, uri) ?: uri.lastPathSegment ?: "Subtitle"
        val option = SubtitleOption(uri.toString(), name)
        externalSubtitles = (externalSubtitles + option).distinctBy { it.uri }
        selectedSubtitleUri = option.uri
    }
}
```

Add the sibling-scan effect and the cue-loading effect (place with the other `LaunchedEffect` blocks, after the `playerViewModel.load(currentItem.uri)` effect):

```kotlin
LaunchedEffect(currentItem.uri) {
    siblingSubtitles = SiblingSubtitleScanner.scan(
        context = context,
        videoFolderName = currentItem.folderPath.substringAfterLast('/'),
        videoFileName = currentItem.displayName,
    )
}

LaunchedEffect(selectedSubtitleUri) {
    val uri = selectedSubtitleUri
    subtitleCues = if (uri == null) emptyList() else SubtitleLoader.load(context, uri)
}
```

Pass the six new params into the `PlayerControls(...)` call (add alongside the existing args):

```kotlin
    subtitleOptions = subtitleOptions,
    selectedSubtitleUri = selectedSubtitleUri,
    subtitleOffsetMs = subtitleOffsetMs,
    onSelectSubtitle = { selectedSubtitleUri = it },
    onLoadSubtitleFile = { subtitlePicker.launch(arrayOf("*/*")) },
    onNudgeSubtitle = { delta -> subtitleOffsetMs = nudgeSubtitleOffset(subtitleOffsetMs, delta) },
```

Add the `CueOverlay` as a child of the root player `Box`, after the `PlayerControls` `AnimatedVisibility` block and before the locked overlay, so cues paint above the video but the lock overlay still covers everything when locked. Hidden in PiP:

```kotlin
if (!inPip) {
    CueOverlay(
        text = activeCueText(subtitleCues, state.positionMs, subtitleOffsetMs),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 72.dp),
    )
}
```

Add a private helper at the bottom of `PlayerScreen.kt` (file scope, outside the composable):

```kotlin
private fun subtitleDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
```

> `Modifier.align`, `Alignment.BottomCenter`, `navigationBarsPadding`, `padding`, `dp` are already used in this file. Add only what the compiler reports missing.

- [ ] **Step 4: Build to verify it compiles and nothing regressed**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing app unit tests still green (47).

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(player): custom subtitle render — cue overlay, CC menu, SAF pick, ±50ms nudge"
```

---

### Task G2.3: Device smoke on the `videoplayer` AVD **[Android-verify]**

**Goal:** Confirm external subtitle load → render → nudge works end-to-end on a real emulator, and the sibling scan is honest (offers a readable sibling when MediaStore exposes one). This is the controller's verification step (run after both code tasks pass review), not a subagent task.

- [ ] **Step 1: Boot the dedicated emulator** (per the memory file)

```bash
~/Library/Android/sdk/emulator/emulator -avd videoplayer -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -no-audio &
/opt/homebrew/bin/adb wait-for-device
```

- [ ] **Step 2: Create a real SRT and push it next to a test clip**

Write `/tmp/vpclips/clipA40s-blue-440hz.srt` with a few cues spanning the 40s clip (e.g. `00:00:01,000 --> 00:00:05,000` "SUBTITLE LINE ONE", `00:00:08,000 --> 00:00:12,000` "second cue", etc.), push it to `/sdcard/Movies/VPTest/`, and media-scan it so MediaStore may index it:

```bash
/opt/homebrew/bin/adb push /tmp/vpclips/clipA40s-blue-440hz.srt /sdcard/Movies/VPTest/
/opt/homebrew/bin/adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/VPTest/clipA40s-blue-440hz.srt
```

- [ ] **Step 3: Install + launch, open clipA**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:installDebug
/opt/homebrew/bin/adb shell am start -n com.videoplayer.app/.MainActivity
```

- [ ] **Step 4: Verify (pause first so controls persist; resize screenshots `sips -Z 1600`, scale taps ×1.5 per the memory gotchas):**
  - **A. CC menu opens** — tap the **CC** button in the secondary control row; the dropdown shows `Off`, possibly the sibling `clipA40s-blue-440hz.srt`, and `Load subtitle file…`.
  - **B. Sibling render (best-effort)** — if the sibling appears, select it; confirm the cue overlay shows "SUBTITLE LINE ONE" around 1–5s. (If MediaStore did not index it, proceed to C — this is the documented scoped-storage caveat.)
  - **C. SAF load (reliable path)** — tap `Load subtitle file…`, pick the .srt via the system document picker; confirm the cue overlay renders the cue text in sync with playback.
  - **D. Nudge** — open CC, tap `Sync +50ms` several times; confirm the `Offset: N ms` readout climbs and the on-screen cue timing shifts earlier; `Sync −50ms` reverses it.
  - **E. Off** — select `Off`; confirm cues disappear.
  - **F. No regression** — play/pause, seek, lock, PiP (Home), background audio still work; no crash in logcat (`adb logcat -d | grep -i "AndroidRuntime.*com.videoplayer"`).

- [ ] **Step 5: Record the smoke result in the ledger** (controller does this, not a subagent).

---

## Self-Review

- **Spec coverage (G-2 section of the spec):** SubtitleLoader ✅ (Step G2.1.2), SAF pick + persistable permission ✅ (G2.2.3), sibling scan via MediaStore.Files filtered by `findSiblingSubtitles` ✅ (G2.1.3), CC menu (Off / externals / Load file…) ✅ (G2.2.2), Compose cue overlay via `activeCueText` ✅ (G2.2.1+G2.2.3), ±50ms nudge via `nudgeSubtitleOffset` ✅ (G2.2.2+G2.2.3), hidden in PiP ✅ (G2.2.3 `!inPip`). Device-verify external load+render+nudge ✅ (G2.3).
- **Out of scope (correctly deferred):** embedded Media3 text tracks → G-3; per-file `subtitleTrackId`/`subtitleOffsetMs` persistence → G-4 (state is reset per file here, by re-keying on `currentItem.uri`).
- **Placeholder scan:** concrete code in every code step.
- **Type consistency:** `SubtitleOption(uri,label)` used identically across loader/scanner/controls/screen; `selectedSubtitleUri: String?` (null = Off) consistent; `onNudgeSubtitle(Long)` fed `±SUBTITLE_NUDGE_MS`, applied via `nudgeSubtitleOffset`; `activeCueText(cues, positionMs, offsetMs)` matches the G-1 signature.
- **Purity:** no new `:core:*` code; all Android/Compose in `:app`.
- **No new deps/permissions:** confirmed (SAF + existing read permission; core icons only).
