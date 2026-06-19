# P1.G — Basic Subtitles (SRT/VTT) Design

**Date:** 2026-06-19
**Status:** Approved (user sign-off 2026-06-19). Drive to completion autonomously.
**Roadmap:** `docs/superpowers/plans/2026-06-17-phase0-phase1-foundation-mvp.md` → P1.G
**Depends on:** Phase 0 + P1.A–P1.F (all merged + pushed @ `94c5ddd`).

## Goal

Deliver V1 "basic subtitles": embedded text tracks, external SRT/VTT files (manual
pick + best-effort sibling scan), per-file subtitle + offset memory, and a ±50ms
sync nudge. ASS/SSA, PGS, and the OpenSubtitles favorite-language flow are Phase 2
(per the master prompt) and explicitly out of scope here.

## User-approved decisions (2026-06-19)

1. **Sync nudge:** build it now — a custom SRT/VTT parser + Compose subtitle
   overlay so a ±50ms timing offset works on external/sibling subs. (Embedded
   tracks render via Media3, no offset.)
2. **Sibling subtitles:** offer, don't auto-show. A same-name sibling appears in
   the subtitle (CC) menu ready to pick; nothing is displayed until the user
   selects it.

## Architecture — a deliberate two-path split

Only one subtitle is active at a time, so the two render paths never collide.

- **Embedded text tracks** (inside the container): selected via Media3 track
  selection (`TrackSelectionParameters`), rendered by `PlayerView`'s built-in
  `SubtitleView` (this respects system caption styles — an accessibility win).
  No timing offset (embedded subs are normally in sync).
- **External + sibling SRT/VTT**: parsed by our own pure-Kotlin parser, rendered
  by a **Compose cue overlay** that selects the active cue by `position + offset`.
  These are NOT added as Media3 `SubtitleConfiguration` (that would double-render);
  Media3 text output stays disabled while a custom sub is active.

## Components & interfaces

### `:core:playback` (pure Kotlin, TDD — the logic core)
```kotlin
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

fun parseSrt(content: String): List<SubtitleCue>     // tolerant: skips malformed blocks
fun parseVtt(content: String): List<SubtitleCue>     // WEBVTT header + cue timings
fun parseSubtitles(content: String, isVtt: Boolean): List<SubtitleCue>  // dispatch

/** Active cue text at [positionMs] shifted by [offsetMs] (offset>0 shows cues earlier). null if none. */
fun activeCueText(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long): String?

/** Sibling subs for a video: base-name match + .srt/.vtt, optional .<lang>. infix. */
fun findSiblingSubtitles(videoFileName: String, candidates: List<String>): List<String>

const val SUBTITLE_NUDGE_MS: Long = 50
fun nudgeSubtitleOffset(currentMs: Long, deltaMs: Long): Long   // step + clamp (e.g. ±600_000)
```

### `:app`
- **`SubtitleLoader`** (e.g. `app/.../player/subtitle/`): `suspend fun load(uri: String): List<SubtitleCue>`
  — reads via `ContentResolver`, picks parser by extension/mime, returns parsed cues.
  Errors → empty list (logged), never crash.
- **External pick**: `ActivityResultContracts.OpenDocument` (mime `*/*` filtered to
  `.srt`/`.vtt`/`application/x-subrip`/`text/vtt`); take a **persistable** URI permission.
- **Sibling scan**: best-effort via `MediaStore.Files` query for files in the video's
  folder whose display name matches `findSiblingSubtitles`. Scoped storage (API 29+)
  cannot freely read arbitrary `.srt` by path — manual pick is the guaranteed path;
  sibling scan only populates the menu when MediaStore exposes a readable URI.
- **Engine text-track surface** (for embedded): `PlaybackState` gains
  `textTracks: List<TextTrackInfo>` (`TextTrackInfo(id: String, label: String, language: String?)`,
  pure data class in `:core:playback`) and `selectedTextTrackId: String?`.
  `PlaybackEngine` gains `fun selectEmbeddedTextTrack(id: String?)` (null = disable).
  Media3 impl: populate from `onTracksChanged` (text `Tracks.Group`s; stable id e.g.
  `"text:<groupIndex>:<trackIndex>"`), select via `TrackSelectionParameters` override,
  disable via `setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`.
- **`PlayerScreen`**:
  - A **CC button** in the top control row → a subtitle `DropdownMenu`:
    `Off` · embedded tracks · sibling/loaded externals · `Load subtitle file…`.
  - **Compose cue overlay**: when a custom (external/sibling) sub is selected,
    show `activeCueText(cues, state.positionMs, offsetMs)` near the bottom (above
    the control bar), with a readable scrim. Hidden in PiP.
  - **±50ms nudge** controls (shown only when a custom sub is active): two buttons
    `−50ms` / `+50ms` adjusting `subtitleOffsetMs` via `nudgeSubtitleOffset`.
  - Embedded selection drives `engine.selectEmbeddedTextTrack`; PlayerView's
    SubtitleView renders it. Selecting a custom sub disables embedded text.
- **Per-file memory** (P1.C reserved columns):
  - `subtitleTrackId: String?` encodes the selection: `null`/`"off"` = none,
    `"embedded:<id>"` = an embedded track, `"ext:<uri>"` = an external/sibling URI.
  - `subtitleOffsetMs: Long?` = the custom-sub offset.
  - Persist on change + on save; restore on open (re-load cues for an `ext:` uri;
    re-select an embedded id when that track exists).

## Sub-packages

- **G-1 — pure core (TDD):** `SubtitleCue`, `parseSrt`/`parseVtt`/`parseSubtitles`,
  `activeCueText`, `findSiblingSubtitles`, `SUBTITLE_NUDGE_MS`/`nudgeSubtitleOffset`,
  `TextTrackInfo`. Full red-green coverage incl. malformed input.
- **G-2 — external + sibling custom path:** `SubtitleLoader`, SAF pick, sibling scan,
  CC menu (Off / externals / Load file…), Compose cue overlay, ±50ms nudge. The big
  integration task. Device-verify external load + render + nudge.
- **G-3 — embedded text tracks:** engine text-track surface (`PlaybackState.textTracks`/
  `selectedTextTrackId`, `selectEmbeddedTextTrack`), CC-menu integration, PlayerView
  SubtitleView. `FakePlaybackEngine` updated. Device-verify embedded selection.
- **G-4 — per-file memory + device verify:** persist/restore `subtitleTrackId` +
  `subtitleOffsetMs`; full subtitle device smoke + whole-feature review.

## TDD targets (pure `:core:playback`)
parseSrt / parseVtt (well-formed + malformed + empty), activeCueText (before/at/after
cue boundaries, with +/- offset), findSiblingSubtitles (exact, `.lang.` infix, wrong
ext, no match, case-insensitive), nudgeSubtitleOffset (step + clamp).

## Constraints preserved
- `:core:*` stays pure (parsers, matchers, data classes — no `android.*`/Compose/Media3).
- No new permissions (SAF grants per-file URI access; no broad storage permission).
- No new heavy deps; no `material-icons-extended` (CC button = `TextButton("CC")` or a
  core icon such as `Icons.Default.Subtitles` if present, else text).
- minSdk 24; SAF + MediaStore.Files behavior gated for scoped storage.
- Commit after every green step; device-verify each sub-package on the `videoplayer` AVD.
- Must not regress P1.A–P1.F (controls, gestures, resume, locks, PiP, background audio,
  playlist/auto-advance, settings).

## Risks
- **Sibling scan on scoped storage** is best-effort; manual SAF pick is the reliable
  path. Be honest in the UI/menu (don't promise siblings that can't be read).
- **Custom cue overlay vs control bar** z-order/overlap — position the overlay above
  the control area; hide it in PiP and (optionally) when controls are visible.
- **Embedded track id stability** across `onTracksChanged` — derive ids from group/track
  indices consistently; re-resolve a remembered embedded id by matching language/label
  when indices shift.
- **Cue lookup performance** — `activeCueText` runs on every position tick; keep it
  O(log n) (cues are sorted) or O(n) with small n; verify no jank.
