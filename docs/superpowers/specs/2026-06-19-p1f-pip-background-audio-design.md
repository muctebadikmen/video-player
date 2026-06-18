# P1.F — PiP + Background Audio (Design)

**Date:** 2026-06-19
**Status:** Approved (user sign-off 2026-06-19). Drive to completion autonomously.
**Roadmap:** `docs/superpowers/plans/2026-06-17-phase0-phase1-foundation-mvp.md` → P1.F
**Depends on:** P1.A–P1.E-2 (all merged + pushed @ `a0d0fd5`).

## Goal

Deliver the V1 must-have "PiP/floating + background audio": when the user leaves
the player, video continues in a Picture-in-Picture window where supported and
falls back to background audio with a notification; lock-screen / notification
transport controls including next/previous; a first Settings screen with a
"Background playback" toggle. "Open and play, never think."

## User-approved product decisions (2026-06-19)

1. **Leave-the-player behavior:** Auto-PiP → background audio. Pressing Home (or
   switching apps) drops the video into a floating PiP window and keeps playing
   on supported devices; if PiP is unavailable or its window is closed, audio
   keeps playing in the background with a notification.
2. **Control:** Build a real Settings screen now with a "Background playback"
   on/off toggle (default **on**). First Settings UI in the app.
3. **Notification controls:** Full play/pause + seek **+ next/previous**, backed
   by moving the current folder into the player's playlist.

## Core architectural move

Today `Media3PlaybackEngine` owns an `ExoPlayer` created **per item** in
`PlayerScreen` (`remember(item.uri)`) and **released on view dispose**
(`AndroidView.onRelease`). That is incompatible with background playback — the
player must outlive the visible screen.

**Change:** introduce `PlaybackService : MediaSessionService` that owns a single
long-lived `ExoPlayer` + a `MediaSession`. The UI binds via a `MediaController`
(which implements `Player`).

- The `PlaybackEngine` interface seam is **unchanged**. `Media3PlaybackEngine`
  becomes a service client: internally it holds a future-resolved
  `MediaController` instead of a directly-built `ExoPlayer`. `attachToView(view)`
  sets `view.player = controller`; `state`/`play`/`pause`/`seekTo`/`setSpeed`
  delegate to the controller; the same `Player.Listener` + position poller drive
  the `state` StateFlow.
- Media3's `MediaSessionService` + default media notification provider supply the
  notification and lock-screen controls (play/pause + seek, and next/prev once a
  playlist exists) — no hand-rolled notification.
- *Rejected alternative:* a hand-rolled foreground service + manual notification.
  More code, more bugs; Media3 already does this correctly.

**Engine lifetime** moves from *per-item* to *per-player-session*: created when
entering the player, released (and the service stopped) when pressing **back to
the library**. Pressing **Home keeps it alive** (PiP / background audio).

## Phasing — four green-committable, device-verifiable sub-packages

### F-1 — PlaybackService + MediaController rebind (background audio)
The riskiest refactor; do first and stabilize.
- New `PlaybackService : MediaSessionService` with one `ExoPlayer`
  (`SeekParameters.CLOSEST_SYNC`, audio attributes + handleAudioBecomingNoisy) +
  `MediaSession`. `onTaskRemoved` stops if not playing.
- Rework `Media3PlaybackEngine` to connect via
  `MediaController.Builder(context, SessionToken(…PlaybackService…)).buildAsync()`,
  queueing commands until connected. `release()` releases the **controller**, not
  the service player; service stopped on back-to-library.
- `PlayerScreen`: engine created once per player session (not per `item.uri`);
  release on `onBack`, keep alive on `ON_STOP`. Single-item playback retained in
  F-1 (playlist arrives in F-3) to isolate the service refactor.
- Manifest: `<service android:name=".playback.PlaybackService"
  android:foregroundServiceType="mediaPlayback" android:exported="false">` with
  the `MediaSessionService` intent-filter; permissions `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (API 33+, runtime
  request). Add `media3-session` to `:app`.
- **Verify:** Home → audio continues + notification with working play/pause +
  seek; back → playback stops + service gone; resume-on-reopen still works.

### F-2 — Picture-in-Picture
- Manifest: `android:supportsPictureInPicture="true"`,
  `android:launchMode="singleTask"` on MainActivity.
- Auto-enter on Home via `PictureInPictureParams.Builder().setAutoEnterEnabled(true)`
  (API 31+); explicit PiP button in controls (API 26+). Below API 26: PiP hidden,
  background audio still applies.
- `onPictureInPictureModeChanged`: hide all overlays/gestures in PiP, show only
  video; restore on expand.
- Source-rect hint + aspect ratio from `PlaybackState.videoAspectRatio`, clamped
  to Android's allowed PiP aspect range.
- New `PipController` activity-interface (mirrors the existing `HardwareKeyGuard`
  pattern) so `PlayerScreen` can request PiP and observe PiP mode.
- **Verify:** Home → floating window keeps playing; expand restores full player;
  pre-API-26 emulator → no PiP, background audio instead.

### F-3 — Playlist + next/previous
The trickiest logic rework; touches tuned per-file effects.
- Folder → `ExoPlayer.setMediaItems(...)` with the playlist; current item tracked
  via `currentMediaItem` / index.
- Re-key per-file resume position, speed, aspect, and orientation to fire on
  `onMediaItemTransition` instead of one-shot first-READY (currently keyed on
  `item.uri`). Persist position for the current item.
- Consolidate auto-advance onto the playlist (replaces the
  VideoPlayerApp `selected`-swap advance path). Sleep "end of video" suppression
  preserved.
- Notification next/previous light up automatically.
- **Verify:** notification next/prev; auto-advance; per-file resume + orientation
  correct across transitions; sleep-at-end still suppresses advance.

### F-4 — Settings screen + background-playback toggle
- First Settings screen: minimal Compose screen, DataStore-backed (extend
  `SettingsRepository`) "Background playback" toggle, default **on**.
- Entry point: settings icon in the library top bar.
- Gate F-1/F-2 background behavior on the toggle: off → leaving pauses (no
  service-backed background, no auto-PiP); on → current behavior.
- **Verify:** toggle off → Home pauses; toggle on → Home continues (PiP/audio).

## TDD (pure `:core:playback`) targets
- `pipAvailable(apiLevel: Int, settingEnabled: Boolean): Boolean` (gates to 26+).
- `clampPipAspect(ratio: Float): Pair<Int, Int>` — clamp to Android's PiP aspect
  bounds (~0.418 .. ~2.39) and return a numerator/denominator pair.
- Playlist `nextIndex(current, size, wrap)` / `previousIndex(...)` math.
- `shouldPlayInBackground(settingEnabled: Boolean): Boolean` gate.
Everything else is `[Android-verify]` (`assembleDebug` + `kuran_test` smoke).

## Constraints preserved
- `:core:*` stays free of `android.*` / Compose / Media3.
- Zero telemetry / no new network. New permissions limited to the foreground-
  service + notification trio, all justified by background playback.
- minSdk 24: PiP gated to API 26+, auto-enter to API 31+, notification runtime
  permission to API 33+.
- Commit after every green step; device-verify each sub-package on `kuran_test`.

## Risks
- F-3 re-keys the carefully-tuned per-file resume/orientation effects from
  `item.uri` to media-item transitions — most likely to need a second pass.
  Mitigation: device-verify each per-file behavior individually after F-3.
- MediaController connection is async; the engine must queue/replay commands
  issued before connect (notably `setMediaUri` + resume seek).
