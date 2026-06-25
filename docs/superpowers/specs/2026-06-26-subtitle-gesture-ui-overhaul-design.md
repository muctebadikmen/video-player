# Subtitle · Gesture · Lock · UI Quality Pass — Design

**Date:** 2026-06-26
**Target release:** v1.9.0 (versionCode 11)
**Status:** Approved (proceed to implementation)

## Goal

A focused quality pass on the player, driven by concrete user complaints. Make
the subtitle experience reliable for **Turkish** specifically, make subtitle
**sync** genuinely user-friendly, eliminate the **gesture conflict** while
holding to change speed, make **unlock** fast, organize **settings** (both
app-level and in-player), and lift overall **UI polish**. No feature
regressions; every step committed; pure logic test-driven; `:core:*` stays free
of Android/Media3/Compose.

The OpenSubtitles connection itself already works on the user's device (the user
has a valid API key + login). The subtitle work is therefore **verification +
reliability hardening**, not a rebuild.

---

## Workstream 1 — Subtitle reliability (Turkish-specific)

**Problem.** The OpenSubtitles + sideload pipeline is feature-complete, but the
highest-probability real defect for Turkish content is **character encoding**.
Turkish `.srt` files are commonly **Windows-1254 / ISO-8859-9**, not UTF-8.
Reading them as UTF-8 turns `ç ğ ı ş ö ü İ` into mojibake (`Ã§`, `ÄŸ`, …).

**Approach.**
- New pure-Kotlin charset utility in `:core:playback` (e.g. `SubtitleCharset`):
  1. Honour a UTF-8/UTF-16 BOM if present.
  2. Else attempt strict UTF-8 decode; if it fails validation, fall back to
     **Windows-1254** (Turkish) — the dominant legacy Turkish subtitle encoding.
  3. Expose `decodeSubtitleBytes(bytes): String`.
- Unit tests with real Turkish byte samples (Windows-1254 and UTF-8) asserting
  the Turkish glyphs survive round-trip.
- Wire it into the app-side readers: `SubtitleLoader` (sideloaded/SAF) and
  `SubtitleDownloader` (OpenSubtitles zip entry). Read **bytes**, decode via the
  utility, then hand to the existing `parseSubtitles()`.
- Audit the full path for Turkish: search uses `languages=tr`; favorite-language
  ranking pins `tr`; download → zip extract → load → render. Confirm `.ass/.ssa`
  degrades gracefully (no crash; renders dialogue text if feasible, else ignored).

**Verify.** Unit tests green; emulator check rendering a real Turkish `.srt`
showing correct diacritics in the `CueOverlay`.

## Workstream 2 — Subtitle sync, user-friendly

**Problem.** Sync lives inside the cramped CC dropdown (tiny ±50 ms / ±0.001
buttons, two-point sync as raw menu items). Hard to discover and operate.

**Approach.** A dedicated **Subtitle Sync sheet** (Material3 `ModalBottomSheet`):
- A large, live readout: `Delay  +250 ms` (ahead/behind clearly labelled).
- Big **−** / **+** delay buttons (±50 ms; long-press or repeated taps to move
  fast), and a **Reset to 0** action.
- The two-point precise sync flow presented as a clear guided sequence
  ("1 · Play to a line and tap *Mark first* … 2 · …") with status text — reusing
  the existing `twoPointSync()` core math, not raw menu items.
- Reachable from the CC menu and/or a dedicated control; the existing per-file
  persistence of `subtitleOffsetMs` / `subtitleRate` is preserved unchanged.

**Verify.** Emulator: open external subtitle, adjust delay, confirm cue timing
shifts live and persists across reopen; run the two-point flow.

## Workstream 3 — Gesture conflict (hold-to-speed lockout)

**Problem.** `PlayerScreen.kt` stacks independent `pointerInput` blocks with no
mutual exclusion; the hold-to-speed handler never consumes events, so holding +
dragging *also* fires seek/volume/brightness.

**Approach.** When the long-press speed boost engages, set `speedBoostActive =
true`; the vertical (brightness/volume) and horizontal (seek) drag handlers
early-return while it is true; the hold handler consumes its pointer events.
Reset on finger lift. Applies to one- and two-finger holds. The small piece of
decision logic is unit-tested.

**Verify.** Unit test for the lockout predicate; emulator: hold to 2×, drag
around — no seek/volume/brightness HUD appears; release restores speed.

## Workstream 4 — Fast unlock

**Problem.** `UNLOCK_HOLD_MS = 3000` (`ScreenControls.kt`) — a 3-second motionless
hold.

**Approach.** Reduce to **700 ms**. Keep the progress ring (now fills quickly) so
accidental pocket-unlocks are still prevented. Keep the lock hint easy to
re-summon. Confirm no other timing makes unlock feel slow.

**Verify.** Emulator: lock, then ~0.7 s hold unlocks; a quick tap does not.

## Workstream 5 — Settings organization

**App Settings screen.** Surface persisted-but-hidden options and group cleanly:
- **Playback** — resume on/off, default speed, background playback.
- **Gestures** — hold speed (1- & 2-finger) [existing].
- **Subtitles** — appearance (style/size/position) [existing], encoding note.
- **Account** — OpenSubtitles login/credentials [existing].
- **Library** — grid columns, view defaults.
- **About** — version, license.
Use consistent section headers, dividers, and spacing.

**In-player.** Declutter the overlay: keep frequent controls inline (transport,
seek, CC, lock, aspect); move rarely-used controls (sleep timer, A-B loop,
frame-step, orientation, subtitle style) into a clean grouped **Player options**
bottom sheet. Every existing feature preserved and still persisted.

**Verify.** Emulator walkthrough of every moved control; no feature lost.

## Workstream 6 — UI polish

- **Typography:** define a real Material3 type scale in `theme/Type.kt`
  (the codebase's explicitly-acknowledged gap) and apply to titles, section
  headers, card titles. Highest perceived-quality lever.
- Spacing consistency in player controls (unify cramped `spacedBy(4dp)`),
  search-field focus/cursor polish, scrim/duration-chip consistency across tiles.
- Tasteful refinement, not a redesign; respects the existing AMOLED/Material You
  theme.

**Verify.** Emulator screenshots of library + player before/after; visual review.

---

## Execution model

- **Subagent-driven**, fresh context per workstream. TDD for pure logic
  (encoding, sync math, gesture lockout). Emulator verification for UI.
- **Sequencing.** WS3, WS4, WS2, WS5, WS6 all touch `PlayerScreen.kt` /
  `PlayerControls.kt`, so they run **sequentially** to avoid collisions. WS1
  (subtitle core + loaders) is largely isolated and can run alongside the
  typography (`theme/Type.kt`) groundwork.
- Commit after every green step. Branch: `feat/subtitle-gesture-ui-overhaul`.
- Version bump to **1.9.0 / versionCode 11**; signed release; push; provide the
  new download URL.

## Constraints

- `:core:model` and `:core:playback` must stay free of `android.*`,
  `androidx.compose.*`, `media3` imports.
- JDK 21 (JBR) for all Gradle calls.
- No new dependencies without explicit approval (the charset fallback uses the
  JVM's built-in `windows-1254` charset — no new library).

## Out of scope (YAGNI)

No `.ass/.ssa` full styling engine (only graceful handling), no subtitle editor,
no new playback engine work, no swipe-to-change-video, no cloud sync.
