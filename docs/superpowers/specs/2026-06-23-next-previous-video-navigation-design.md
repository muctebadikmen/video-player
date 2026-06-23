# Next / Previous Video Navigation — Design

**Date:** 2026-06-23
**Status:** Approved (proceed to implementation)

## Goal

Add **previous** and **next** video controls to the player, flanking the
center play/pause button, behaving like mainstream mobile players (YouTube,
MX Player).

- **Next (►►):** always jump straight to the next video in the queue,
  regardless of current playback position.
- **Previous (◄◄):** if more than ~3 seconds have elapsed, restart the
  current video at 0:00. Press again (now <3 s in) → jump to the previous
  video. At the first video it simply restarts.

## Key realization

The player already loads the **entire folder as an ExoPlayer playlist**
(`PlaybackEngine.setMediaPlaylist(uris, startIndex)` in
`PlayerScreen`), and the Media3 `MediaController` **is** a `Player` — so
`seekToNext()` and the queue are already there.

**On-device finding:** routed through a `MediaController` (the app talks to the
service-owned player via a controller), `Player.seekToPrevious()` does **not**
honor `maxSeekToPreviousPositionMs` — at 4 s it still stepped back instead of
restarting. So the engine applies the 3-second decision itself, using the
controller's live position (`C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS` = 3000):
within the first 3 s (and not the first item) step back, otherwise restart the
current item. This guarantees the exact behavior the spec requires regardless of
controller/SDK quirks.

## Changes (5 files)

1. **`:core:playback` `PlaybackEngine`** — add `seekToNext()` and
   `seekToPrevious()` to the interface (engine-agnostic; libmpv can implement
   the same contract later).
2. **`Media3PlaybackEngine`** — `seekToNext()` delegates to the controller;
   `seekToPrevious()` applies the 3 s threshold itself (see Key realization).
   Both refresh `_state.positionMs` after the seek so the seek bar updates even
   while paused (the position poller only runs during playback).
3. **`FakePlaybackEngine`** — model the contract deterministically (track the
   playlist; next advances the index, previous restarts-or-steps-back using a
   3000 ms threshold mirroring Media3) so it stays a faithful test double.
4. **`PlayerControls`** — replace the single centered play/pause button with a
   centered row: **⏮ previous · ⏯ play/pause · ⏭ next**
   (`Icons.Filled.SkipPrevious` / `SkipNext`). New params: `onPrevious`,
   `onNext`, `hasNext`.
5. **`PlayerScreen`** — wire `onPrevious`/`onNext` to the engine; persist the
   outgoing video's resume position first (call the existing `saveNow()` so
   smart-resume stays correct across manual navigation); compute
   `hasNext = state.currentMediaIndex < playlist.lastIndex`.

## Enable / boundary behavior

- **Next** is disabled + greyed on the **last** video (no wrap-around —
  standard for a folder queue). Computed from data already in `PlayerScreen`
  (`currentMediaIndex` + `playlist.size`); no `PlaybackState` change needed.
- **Previous** is always enabled (worst case it restarts the current video).
- Switching reuses the existing per-file load path, so the new video gets its
  saved resume position, speed, aspect, and subtitle automatically — identical
  to opening it from the library.

## Testing

- The real 3 s threshold lives inside ExoPlayer; the meaningful proof is an
  **on-device emulator check**: mid-video previous → restart; previous again →
  previous file; next → next file; next disabled on the last item.
- Unit test `FakePlaybackEngine`'s next/previous contract (index advance,
  restart-vs-step-back threshold, queue boundaries). No faking of ExoPlayer
  internals.

## Out of scope (YAGNI)

No swipe-to-change-video gesture, no autoplay toggle, no shuffle/repeat — just
the two buttons.
