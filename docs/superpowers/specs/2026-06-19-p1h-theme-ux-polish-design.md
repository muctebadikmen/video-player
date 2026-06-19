# P1.H — Theme & UX Polish Design

**Date:** 2026-06-19
**Status:** Approved for autonomous execution (per the drive-to-completion directive; defensible defaults chosen for the scope forks below).
**Roadmap:** `docs/superpowers/plans/2026-06-17-phase0-phase1-foundation-mvp.md` → P1.H
**Depends on:** Phase 0 + P1.A–P1.G (all merged + pushed @ `533054c`).

## Goal

Remove the remaining rough edges and accessibility gaps so the app feels finished for a first F-Droid release: a proper library loading/empty state, no brightness leak on exit, real icons (no placeholders, no heavy icon dependency), thumbnail accessibility, and small deferred code cleanups. Theme is already Material You + true-black AMOLED (Phase 0); this package confirms it rather than rebuilding it.

## Scope decisions (forks resolved)

- **Customizable control bar — DEFERRED** to a post-V1 release. It is in the aspirational v1-scope but is *configurability* (counter to "simplicity first"), the default control bar is already complete and usable, and it would add a settings + serialization surface not required for a polished first release. A lean MVP ships without it.
- **Theme picker — NOT added.** Material You dynamic color (API 31+) with a true-black AMOLED dark scheme is the correct default for a video player; a light/dark/AMOLED selector is V1 scope creep.
- **Sleep-at-end "stays armed" — KEPT** (pauses at each video end until turned off; the 💤 control already highlights when active). Defensible; one-shot is a noted later refinement.
- **Custom cue-overlay system-caption styling — DEFERRED to V2 a11y.** Embedded subtitle tracks already render through `PlayerView`'s `SubtitleView`, which respects the system caption style; the external/sibling custom overlay uses a fixed readable style (white text + scrim) for V1.

## Confirmed issues (in scope)

1. **Library loading state.** `LibraryScreen` renders a bare "No videos found" (with no top bar) whenever folders, videos, and continue-watching are all empty — which includes the window *before* the permission resolves and the MediaStore scan completes. Result: a "No videos found" flash on cold start (observed on device). Fix: track a loading state and distinguish "loading" from "empty after a completed scan".
2. **Brightness leak on exit.** The brightness gesture sets `window.attributes.screenBrightness`; it is never reset, so the override persists into the library and other apps until the activity is recreated. Fix: restore `BRIGHTNESS_OVERRIDE_NONE` when the player leaves composition.
3. **Placeholder icons.** The library's Sort action uses `Icons.Default.MoreVert` and the grid-view toggle uses `Icons.Default.Menu` — both placeholders. Fix with small hand-authored `ImageVector`s (no `material-icons-extended`, to keep the APK small and the dependency graph FOSS-lean for F-Droid).
4. **Thumbnail accessibility.** The library thumbnail `Image`s pass `contentDescription = null`. Fix: describe each with its video name (and keep decorative icons null).
5. **Code cleanups (low user impact).** Move `ThumbnailLoader` out of `data/memory/` (it is UI image decoding, not persistence) into the `library/` package; replace the two fully-qualified `FRAME_STEP_MS` references with an import; add the two deferred clarifying comments (duration-sleep spans auto-advance; any other P1.E note).

## Architecture & components

All changes are in `:app` (UI/Compose). No `:core:*` change, no new dependencies, no new permissions. There is little pure logic here — this package is honestly **[Android-verify]** (built via `:app:assembleDebug` + existing unit suites staying green, then an emulator smoke), with one small testable seam (the loading-state predicate) extracted where it adds value.

### H-1 — Library polish (loading/empty state, icons, thumbnail a11y)

- **`LibraryUiState`** gains `isLoading: Boolean` (default `true`). `LibraryViewModel` sets it `false` after the first folders/memory emission (or after `refresh()` completes), so the UI can tell "still scanning" from "scanned, nothing found".
  - Optional small pure helper for the screen's branch: `libraryContentState(isLoading, hasAnyItems)` → `LOADING | EMPTY | CONTENT` — if extracted, unit-test it; otherwise inline the `when`.
- **`LibraryScreen`** always renders the top bar (search + sort + view toggle + settings). The body shows: a centered progress indicator when `LOADING`, the "No videos found" empty state only when `EMPTY` (scan complete, zero items), and the list/grid otherwise.
- **Icons:** add `app/.../library/LibraryIcons.kt` with two `ImageVector`s — `GridViewIcon` (2×2 squares) and `SortIcon` (descending bars) — built with the standard `ImageVector.Builder` path DSL, tinted via `LocalContentColor`. Replace `Menu`→`GridViewIcon` (grid toggle) and `MoreVert`→`SortIcon` (sort). Keep `Icons.AutoMirrored.Filled.List` for the list toggle (already appropriate) and the core `Search`/`Settings`/`Check` icons.
- **Thumbnail a11y:** the two thumbnail `Image`s get `contentDescription = item.displayName` (the row/tile already has the name; this is for TalkBack when navigating by image). Progress overlays / decorative icons stay null.

### H-2 — Player polish + cleanups

- **Brightness reset:** in `PlayerScreen`, add a `DisposableEffect(activity)` (or extend the existing orientation one) whose `onDispose` sets `window.attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`. This restores system-controlled brightness when the user leaves the player. (Mirrors the existing orientation reset-on-dispose.)
- **`ThumbnailLoader` move:** relocate `app/.../data/memory/ThumbnailLoader.kt` → `app/.../library/ThumbnailLoader.kt` (package `com.videoplayer.app.library`), update its package declaration and all imports/usages. Pure move, no behavior change.
- **`FRAME_STEP_MS` import:** replace the two fully-qualified references with a top-level import.
- **Comments:** add the clarifying comment that the duration sleep timer spans auto-advance (vs the per-file A–B loop), plus any other small deferred note.

## Testing

- **[TDD]** only where a pure seam exists: `libraryContentState(...)` if extracted (LOADING/EMPTY/CONTENT). Otherwise the package is integration/UI.
- **[Android-verify]** `:app:assembleDebug` + `:app:testDebugUnitTest` green after each task; emulator smoke at the end of each sub-package: cold-start shows a spinner not a premature "No videos found"; sort/grid icons render and work; thumbnails have content descriptions (TalkBack optional); brightness gesture no longer leaks after returning to the library; no regressions to P1.A–P1.G.

## Constraints preserved

- `:core:*` untouched; all changes in `:app`.
- No new dependencies (no `material-icons-extended`; icons are hand-authored vectors). No new permissions.
- Must not regress P1.A–P1.G (player controls, gestures, resume, locks, PiP, background audio, playlist/auto-advance, settings, subtitles).
- Commit after every green step; device-verify each sub-package on the `videoplayer` AVD.

## Deferred (recorded, not built here)

- Customizable control bar (configurability) → post-V1.
- Theme/AMOLED selector → not needed for V1 (dark/AMOLED default is correct).
- Sleep-at-end one-shot behavior → keep stays-armed.
- Custom cue-overlay respecting system caption styles → V2 a11y (embedded already does).
- Scrub thumbnails on the seek bar (a v1-scope item not yet built) → tracked for a later package; not part of P1.H polish.
