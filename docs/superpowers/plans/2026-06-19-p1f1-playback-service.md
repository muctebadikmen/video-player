# P1.F-1 — PlaybackService + MediaController (background audio) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the ExoPlayer into a long-lived `MediaSessionService` so playback survives the player screen leaving the foreground (background audio), with a system media notification, while keeping the `PlaybackEngine` interface seam intact.

**Architecture:** A new `PlaybackService : MediaSessionService` owns one `ExoPlayer` + a `MediaSession`. `Media3PlaybackEngine` becomes a client that connects via a `MediaController` (which implements `Player`) instead of building its own `ExoPlayer`. The engine surfaces the service player's audio session id through `PlaybackState.audioSessionId` so the existing `VolumeController` (LoudnessEnhancer 200% boost) keeps working. The per-item engine lifecycle (`remember(item.uri)`) is retained; "back to library" explicitly stops the service player while "Home" keeps it alive.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Media3 1.5.1 (`media3-exoplayer`, `media3-ui`, `media3-session`), Guava ListenableFuture (transitive via media3-session).

## Global Constraints

- Build with JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew`.
- `:core:*` modules MUST NOT import `android.*`, `androidx.compose.*`, or `media3`. Pure Kotlin + coroutines only.
- Zero telemetry / no network. New permissions limited to `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.
- minSdk 24, targetSdk 35, compileSdk 35. `POST_NOTIFICATIONS` is API 33+ (runtime request; playback still works if denied).
- Commit after every green step; conventional-commit messages.
- Do NOT regress: gesture volume boost (LoudnessEnhancer via `VolumeController`), per-file resume/orientation/aspect, auto-advance, Kids Lock.

---

### Task F1.1: Pure background-playback predicate + `PlaybackState.audioSessionId` (TDD)

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/BackgroundPlayback.kt`
- Modify: `core/playback/src/main/kotlin/com/videoplayer/core/playback/PlaybackState.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/BackgroundPlaybackTest.kt`

**Interfaces:**
- Produces:
  - `fun shouldStopOnTaskRemoved(playWhenReady: Boolean, mediaItemCount: Int): Boolean` — true when the service should stop itself after the task is swiped away (nothing meaningful is playing).
  - `PlaybackState` gains `val audioSessionId: Int = 0` (new last field, default 0).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundPlaybackTest {
    @Test fun `stops when not playing`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = false, mediaItemCount = 1)).isTrue()
    }
    @Test fun `stops when no media items`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 0)).isTrue()
    }
    @Test fun `keeps playing when playing with media`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1)).isFalse()
    }
    @Test fun `audioSessionId defaults to zero`() {
        assertThat(PlaybackState().audioSessionId).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:playback:test`
Expected: FAIL — `shouldStopOnTaskRemoved` unresolved and `audioSessionId` unresolved.

- [ ] **Step 3: Implement**

`BackgroundPlayback.kt`:
```kotlin
package com.videoplayer.core.playback

/**
 * Whether a MediaSessionService should stop itself when its task is removed
 * (app swiped from recents). Stop unless something is actively playing.
 */
fun shouldStopOnTaskRemoved(playWhenReady: Boolean, mediaItemCount: Int): Boolean =
    !playWhenReady || mediaItemCount == 0
```

In `PlaybackState.kt`, add the field as the last property (keep existing fields/order):
```kotlin
data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val engine: EngineType = EngineType.MEDIA3,
    val videoAspectRatio: Float = 0f,
    val audioSessionId: Int = 0,
)
```
> Note: copy the EXISTING fields verbatim from the current file (it already has `videoAspectRatio` from P1.E-2). Only ADD `audioSessionId`. Verify the real field list before editing.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:playback:test` (with JAVA_HOME)
Expected: PASS — all BackgroundPlaybackTest + existing tests green.

- [ ] **Step 5: Commit**

```bash
git add core/playback && git commit -m "feat(core): background-playback stop predicate and audioSessionId in state"
```

---

### Task F1.2: `PlaybackService` + manifest + dependency [Android-verify]

**Files:**
- Modify: `app/build.gradle.kts` (add `media3-session`)
- Create: `app/src/main/kotlin/com/videoplayer/app/playback/PlaybackService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `shouldStopOnTaskRemoved` (F1.1).
- Produces: a running `MediaSessionService` named `.playback.PlaybackService` whose session wraps an `ExoPlayer` (CLOSEST_SYNC seek, movie audio attributes, audio-becoming-noisy handling) and publishes its audio session id in the session extras under key `"audioSessionId"`.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, next to the existing media3 lines:
```kotlin
implementation(libs.media3.exoplayer)
implementation(libs.media3.ui)
implementation(libs.media3.session)
```

- [ ] **Step 2: Create `PlaybackService.kt`**

```kotlin
package com.videoplayer.app.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.videoplayer.core.playback.shouldStopOnTaskRemoved

/** Key under which the player's audio session id is published in the session extras. */
const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioSessionId = Util.generateAudioSessionIdV21(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioSessionId(audioSessionId)
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
        val extras = Bundle().apply { putInt(EXTRA_AUDIO_SESSION_ID, audioSessionId) }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionExtras(extras)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            shouldStopOnTaskRemoved(player.playWhenReady, player.mediaItemCount)
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
```

- [ ] **Step 3: Update the manifest**

In `app/src/main/AndroidManifest.xml`, add the three permissions next to the existing `<uses-permission>` lines:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Inside `<application>`, after the `<activity>` block, add:
```xml
<service
    android:name=".playback.PlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

- [ ] **Step 4: Build to verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(app): PlaybackService MediaSessionService with media notification"
```

---

### Task F1.3: Rebind `Media3PlaybackEngine` to a service `MediaController` [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt`

**Interfaces:**
- Consumes: `PlaybackService`, `EXTRA_AUDIO_SESSION_ID` (F1.2); `PlaybackState.audioSessionId` (F1.1).
- Produces: `Media3PlaybackEngine` keeps the SAME public surface — `state: StateFlow<PlaybackState>`, `setMediaUri(String)`, `play()`, `pause()`, `seekTo(Long)`, `setSpeed(Float)`, `release()`, `attachToView(PlayerView)`, `val audioSessionId: Int` — plus a NEW method `fun stop()` that stops + clears the service player (used on exit-to-library). Internally it holds a future-resolved `MediaController` instead of an `ExoPlayer`.

**Background:** The current engine builds `ExoPlayer.Builder(context).build()` and exposes `audioSessionId = player.audioSessionId`. Replace the player source with a `MediaController` connected to `PlaybackService`. Connection is async (`ListenableFuture`); queue commands issued before connect and replay them on connect. Keep the existing `exoStateToStatus`, `videoAspectRatio`, position-polling, and `Player.Listener` logic — they apply to the controller unchanged (`MediaController` IS a `Player`).

- [ ] **Step 1: Read the current file in full** before editing so the listener, position poller, and helper functions are preserved verbatim. Note the exact current shape of `init {}`, `attachToView`, `release`, `setMediaUri`, the position-update functions, and `onVideoSizeChanged`.

- [ ] **Step 2: Rewrite the engine internals to use a `MediaController`**

Replace the `private val player: ExoPlayer = ...` construction and the `init` connection logic. Target shape (adapt names to the existing file; KEEP the existing `Player.Listener` body, `startPositionUpdates`/`stopPositionUpdates`, and `videoAspectRatio` helper):

```kotlin
package com.videoplayer.app.engine

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.videoplayer.app.playback.EXTRA_AUDIO_SESSION_ID
import com.videoplayer.app.playback.PlaybackService
import com.videoplayer.core.playback.EngineType
import com.videoplayer.core.playback.PlaybackEngine
import com.videoplayer.core.playback.PlaybackState
import com.videoplayer.core.playback.PlayerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@UnstableApi
class Media3PlaybackEngine(context: Context) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(PlaybackState(engine = EngineType.MEDIA3))
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var positionJob: Job? = null

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var attachedView: PlayerView? = null
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            _state.update {
                it.copy(
                    status = exoStateToStatus(playbackState),
                    durationMs = c.duration.coerceAtLeast(0),
                )
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(videoAspectRatio = videoAspectRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio))
            }
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) return@addListener
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(listener)
            attachedView?.player = c
            seedStateFromController(c)
            val queued = pending.toList(); pending.clear()
            queued.forEach { it(c) }
            if (c.isPlaying) startPositionUpdates()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun seedStateFromController(c: MediaController) {
        val sessionId = c.sessionExtras.getInt(EXTRA_AUDIO_SESSION_ID, 0)
        _state.update {
            it.copy(
                status = exoStateToStatus(c.playbackState),
                isPlaying = c.isPlaying,
                durationMs = c.duration.coerceAtLeast(0),
                speed = c.playbackParameters.speed,
                audioSessionId = sessionId,
            )
        }
    }

    private inline fun withController(crossinline block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add { block(it) }
    }

    val audioSessionId: Int get() = _state.value.audioSessionId

    override fun setMediaUri(uri: String) = withController { c ->
        c.setMediaItem(MediaItem.fromUri(uri))
        c.prepare()
    }

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }

    override fun seekTo(positionMs: Long) = withController { c ->
        c.seekTo(positionMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
    }

    override fun setSpeed(speed: Float) = withController { c ->
        c.setPlaybackParameters(PlaybackParameters(speed))
        _state.update { it.copy(speed = speed) }
    }

    /** Stop + clear the service player. Used when exiting the player to the library. */
    fun stop() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun attachToView(view: PlayerView) {
        attachedView = view
        controller?.let { view.player = it }
    }

    override fun release() {
        released = true
        stopPositionUpdates()
        scope.cancel()
        controller?.removeListener(listener)
        attachedView?.player = null
        attachedView = null
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _state.value = PlaybackState()
    }

    // KEEP the existing private startPositionUpdates()/stopPositionUpdates() that poll
    // controller.currentPosition into _state every ~500ms. Adapt them to read `controller`
    // instead of `player` (guard null). KEEP the existing top-level exoStateToStatus()
    // and videoAspectRatio() helpers unchanged.
}
```

> IMPORTANT adaptation notes for the implementer:
> - Preserve the existing position poller; just change its source from `player.currentPosition` to `controller?.currentPosition ?: return`.
> - Keep `exoStateToStatus` and `videoAspectRatio` exactly as they are now (do not move or rename — `Media3StateMapperTest` depends on `exoStateToStatus`).
> - `attachToView` previously did `view.player = player`; now it caches the view and attaches when the controller is ready.
> - Do NOT release the service player in `release()` — only the controller. `stop()` is the explicit "kill playback" path.

- [ ] **Step 3: Build + run existing app unit tests**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `Media3StateMapperTest` and all other app unit tests still pass (no behavior change to pure mapper).

- [ ] **Step 4: Commit**

```bash
git add app && git commit -m "feat(app): route Media3 engine through a service MediaController for background playback"
```

---

### Task F1.4: PlayerScreen — stop-on-exit, reactive VolumeController, notification permission [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `Media3PlaybackEngine.stop()` (F1.3), `state.audioSessionId` (F1.1/F1.3).

- [ ] **Step 1: Read PlayerScreen** to locate: the `engine` / `volumeController` `remember`s (~lines 107–111), the `BackHandler { if (!locked) onBack() }` (~line 141), the `AndroidView.onRelease` (~lines 404–407), and the `DisposableEffect` lifecycle/save block (~lines 264–273).

- [ ] **Step 2: Make VolumeController reactive to the audio session id**

The audio session id is now 0 until the controller connects, then becomes the real id. Recreate `VolumeController` when it changes and release the old one. Replace:
```kotlin
val volumeController = remember(engine) { VolumeController(context, engine.audioSessionId) }
```
with:
```kotlin
val audioSessionId = state.audioSessionId
val volumeController = remember(audioSessionId) { VolumeController(context, audioSessionId) }
DisposableEffect(volumeController) { onDispose { volumeController.release() } }
```
> Read `VolumeController` first to confirm its release method name. If it has no release method, add a `fun release()` that releases its `LoudnessEnhancer` and call it; if a `DisposableEffect` already releases it elsewhere, fold this in rather than double-releasing. Preserve current boost behavior.

- [ ] **Step 3: Stop playback when leaving to the library (not on auto-advance)**

Wrap the back action so exiting the player stops the service player, then navigates. Define near the top of the composable (after `engine`):
```kotlin
val exitToLibrary = remember(engine, onBack) {
    {
        engine.stop()
        onBack()
    }
}
```
Use `exitToLibrary` everywhere `onBack()` is currently called for a user-initiated exit:
- `BackHandler { if (!locked) exitToLibrary() }`
- any back arrow / close button in the controls that currently calls `onBack`.
Leave `onAdvance(next)` (auto-advance) untouched — it must NOT stop the player.

- [ ] **Step 4: Do NOT release the engine on ON_STOP**

Confirm the existing `DisposableEffect` only calls `saveNow()` on `Lifecycle.Event.ON_STOP` (it does) and that the engine is released ONLY in `AndroidView.onRelease`. Leave `onRelease { view.player = null; engine.release() }` as-is — on Home press the screen stays composed, so the engine is not released and playback continues. No change needed here beyond confirming.

- [ ] **Step 5: Request POST_NOTIFICATIONS on API 33+**

At the top of `PlayerScreen`, add a one-shot launcher that requests the notification permission so the media notification can show (playback still works if denied):
```kotlin
val notifLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* ignored: playback works without it */ }
LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) {
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```
Add imports: `android.Manifest`, `android.content.pm.PackageManager`, `android.os.Build`, `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.core.content.ContextCompat`.

- [ ] **Step 6: Build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests green.

- [ ] **Step 7: Commit**

```bash
git add app && git commit -m "feat(player): stop on exit, reactive volume boost, notification permission for background audio"
```

---

### Task F1.5: Device smoke on `kuran_test` [Android-verify]

**Goal:** Verify background audio, the notification, exit-stops, and that nothing regressed. This task is run by the orchestrator (not a code subagent).

- [ ] **Step 1: Boot emulator + install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# emulator already booted in prior sessions; if not: ~/Library/Android/sdk/emulator/emulator -avd kuran_test &
/opt/homebrew/bin/adb wait-for-device
./gradlew :app:installDebug
/opt/homebrew/bin/adb shell am start -n com.videoplayer.app/.MainActivity
```

- [ ] **Step 2: Ensure a test clip exists** (re-push if the emulator was cold-booted):
```bash
/opt/homebrew/bin/adb shell ls /sdcard/Movies/ || true
# if empty, push the 5s testsrc clip used in prior sessions and media-scan it.
```

- [ ] **Step 3: Verify checks** (grant POST_NOTIFICATIONS when prompted):
  - **A — Background audio:** open a video, play, press Home (`adb shell input keyevent 3`). Confirm audio keeps playing: `adb shell dumpsys media_session | grep -i "state=PlaybackState {state=3"` (3 = PLAYING) OR audible. 
  - **B — Notification:** `adb shell dumpsys notification --noredact | grep -i "com.videoplayer.app"` shows a media notification with transport actions.
  - **C — Notification play/pause:** toggle via `adb shell media dispatch pause` / `play` (or tap) and confirm state flips in `dumpsys media_session`.
  - **D — Resume from notification → app:** reopen the app; player still bound; position advanced.
  - **E — Back stops:** in the player, press back (`adb shell input keyevent 4`); confirm playback stops and the notification clears (`dumpsys notification` no longer lists it) and `dumpsys activity services | grep PlaybackService` shows it stopped (or absent).
  - **F — No regression:** resume-on-reopen still works (open same clip → resumes near saved position); volume-boost gesture still raises volume (code path intact; emulator may not expose audible boost — confirm no crash).
  - **G — No crash:** `adb logcat -d | grep -i "AndroidRuntime" | grep com.videoplayer` is empty.

- [ ] **Step 4: Record results** in `.git/sdd/progress.md` and the memory file. If E (back-stops) leaves the service lingering, add an explicit stop (e.g., `MediaController.sendCustomCommand` to a service stop command, or `context.stopService(Intent(context, PlaybackService::class.java))` from `exitToLibrary`) and re-verify.

---

## Self-Review

- **Spec coverage:** F-1 section of the spec → Tasks F1.1–F1.5 cover service, controller rebind, background audio, notification, permissions, audio-session preservation, exit-stop. PiP (F-2), playlist/next-prev (F-3), and Settings toggle (F-4) are deliberately out of this plan.
- **Placeholder scan:** none — all code is concrete; the one adaptation ("preserve existing poller/helpers") references real existing code the implementer reads in F1.3 Step 1.
- **Type consistency:** `shouldStopOnTaskRemoved` (F1.1) used in F1.2; `EXTRA_AUDIO_SESSION_ID` (F1.2) used in F1.3; `PlaybackState.audioSessionId` (F1.1) read in F1.3/F1.4; `Media3PlaybackEngine.stop()` (F1.3) used in F1.4. Consistent.
- **Risk:** the engine rewrite (F1.3) is the crux; existing `:app` unit tests + assembleDebug gate it, device smoke (F1.5) confirms runtime behavior.
