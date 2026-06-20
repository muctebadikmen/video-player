<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Subtitle Power & Player Polish — design (v1.1–v1.3)

**Status:** Approved (user delegated all decisions 2026-06-19). Technical decisions resolved via three parallel analyst subagents (OpenSubtitles API, networking stack, Android storage/hash/intents).
**Author:** Mustafa (orchestrated). **Repo:** github.com/muctebadikmen/video-player. **Base:** origin/main @ `d8a14df`.

## Goal & context

The app is now self-distributed (a signed APK installed directly, or via Obtainium pointed at the private repo with a GitHub token), so we iterate fast. This work adds four things the user asked for, shipped as three incremental signed releases:

1. **A — Controls animation fix** (pause/controls overlay must fade in place, not slide in from a corner).
2. **D — "Open with" from a file manager** (the app appears in the chooser for video files and plays them).
3. **C — Manual subtitle sync** with both delay AND speed/rate correction, plus a two-point "precise sync" mode.
4. **B — OpenSubtitles search & download** (the flagship "star feature"): favorite-language search, quality ranking, one-tap download into the existing subtitle pipeline.

**Out of scope (deferred, own future milestone):** libmpv/libass integration for *styled* embedded ASS subtitles and *image-based* PGS embedded subtitles. Investigation confirmed ExoPlayer/Media3 1.5.1 already renders embedded SRT/WebVTT/TTML (and ASS as unstyled text); full ASS styling + PGS need a second native engine. The practical answer for "subs on my MKV" is feature B (download a clean external subtitle), which is why B is in scope and libmpv is not.

## Resolved technical decisions

### Networking stack (feature B) — NEW DEPENDENCIES (approved)
- **OkHttp `4.12.0`** (Apache-2.0) — pin to 4.x (not 5.x) for guaranteed minSdk-24 build; ships its own modern TLS `ConnectionSpec` (fixes API-24 platform-TLS gaps that `HttpURLConnection` would suffer).
- **kotlinx-serialization-json `1.7.3`** (Apache-2.0) — pairs with this project's Kotlin 2.0.21; the serialization compiler plugin ships inside Kotlin (`org.jetbrains.kotlin.plugin.serialization`, `version.ref = "kotlin"`, no extra artifact).
- **MockWebServer `4.12.0`** (Apache-2.0) — **test scope only**, for unit-testing the client.
- Version-catalog coordinates and `app/build.gradle.kts` wiring are specified in the implementation plan. **All third-party network/JSON code lives in `:app` only** — `:core:*` stays pure (no Android, no 3rd-party libs). `@Serializable` DTOs live in `:app`.

### OpenSubtitles REST API (feature B)
- Base `https://api.opensubtitles.com/api/v1` (modern REST API; the legacy XML-RPC API is being shut down).
- **Auth = user supplies their OWN credentials** (correct for an open-source binary — never embed a shared key in a public repo): a Settings screen collects the user's **Api-Key** (from a free "API Consumer" they register) + **username/password**. `POST /login` → `{token, base_url, user:{allowed_downloads, vip}}`. **Switch to the returned `base_url`** for subsequent calls; cache the token (persist), re-login only on 401. `/login` is rate-limited (~5/IP) — never log in per request.
- **Mandatory headers:** `Api-Key`, a **real custom `User-Agent`** (`VideoPlayer v<versionName>` — a default/library UA causes 403), `Accept: application/json`, `Content-Type: application/json` on bodies, `Authorization: Bearer <token>` after login.
- **Search:** `GET /subtitles?moviehash=<h>&query=<name>&languages=en,tr&...`. Rank using `attributes`: `moviehash_match` (exact-file match → top), `download_count`, `ratings`, `from_trusted`, demote `ai_translated`/`machine_translated`. Use `files[].file_id`.
- **Download:** `POST /download {file_id}` → `{link, remaining, reset_time_utc}`; then a plain `GET <link>` (temporary, single-use) for the subtitle bytes. **Read quota live** (`user.allowed_downloads`, `remaining`) — do not hardcode; published free-tier numbers disagree.
- **Errors:** 401 → one token refresh + retry; 406 → quota exhausted (terminal, show reset time); 429/5xx → exponential backoff + jitter (honor `Retry-After`); 403 → check User-Agent / login.
- **OSDb movie-hash:** `hash = (filesize + Σ first-64KiB + Σ last-64KiB)` as little-endian unsigned-64 words, wrap mod 2⁶⁴, render 16 lowercase hex. Files < 128 KiB → no hash (filename search). Golden test vector: `breakdance.avi` size `12909756` → `8e245d9679d31e12`.

### Subtitle storage (feature B)
- Downloaded `.srt` saved to **app-private `context.getExternalFilesDir("subtitles")`** as `<video-stem>.<lang>.srt`; expose via **`Uri.fromFile(...)` (`file://`)**. This flows unchanged into the existing `SubtitleLoader` (`ContentResolver.openInputStream`), is added as a `SubtitleOption`, set as `selectedSubtitleUri`, and persists per-file as `ext:<uri>` via the existing `subtitleMemoryToken`/`parseSubtitleToken`. No new permission; **do NOT** call `takePersistableUriPermission` on a `file://` URI.

### Movie-hash from a content:// URI (feature B)
- `ContentResolver.openFileDescriptor(uri, "r")` → `pfd.statSize` for length + `FileInputStream(pfd.fileDescriptor).channel` for a **seekable** read of the first and last 64 KiB (`channel.position(size-65536)`; never `skip()`). All on `Dispatchers.IO`. If `statSize < 0` or the FD isn't seekable (rare; network DocumentsProvider) → fall back to filename search.

### Open-with intents (feature D)
- Three `<intent-filter>` blocks on `MainActivity`: (1) `ACTION_VIEW` + `video/*` for `content`/`file`; (2) extension/`application/octet-stream` matcher with **`<data android:host="*"/>` + per-extension `pathPattern` (lower- AND upper-case)** for `.mkv/.mp4/.webm/.avi/.mov/.m4v/.ts/.flv/.wmv/.3gp/.mpg/.mpeg/.mts/.m3u8`; (3) `http/https` `video/*` streams. All need `category.DEFAULT` (+ `BROWSABLE` on link-capable ones).
- `MainActivity` (`singleTask`): handle `getIntent()` in `onCreate` AND override `onNewIntent`→`setIntent`. **Read within the grant** (no `takePersistableUriPermission` for VIEW — it usually throws). Synthesize a minimal `MediaItem` from `OpenableColumns.DISPLAY_NAME`/`SIZE` + `MediaMetadataRetriever` duration (off main thread). Play as a single-item session (reuse the existing one-element playlist path).

## Feature designs

### A — Controls animation fix (XS)
`PlayerScreen.kt` wraps the control overlay in `AnimatedVisibility(visible = controlsVisible && !inPip)` with no explicit transition → Compose defaults to expand/shrink-from-corner, which reads as the centered pause button "sliding in from the corner." Fix: `enter = fadeIn(), exit = fadeOut()`. Verify no other overlay (gesture HUDs, lock hint) regressed. Device-verify the tap-to-pause animation is a clean in-place fade.

### D — Open-with (M)
- **Manifest:** the three intent-filters above.
- **MainActivity:** read inbound VIEW URI (onCreate + onNewIntent), hold in state, pass to `VideoPlayerApp`.
- **VideoPlayerApp:** when an external URI is present, bypass the library/`selected` path → single-item session via a synthesized `MediaItem(uri, displayName, …)`. Existing per-file resume/memory keyed on the URI still works; sibling-subtitle scan won't apply to a foreign URI (acceptable — feature B covers it).
- **Edge:** opaque `content://` with no path + `octet-stream` from a minority of file managers won't match (no manifest fix; documented limitation — do NOT use `*/*`).

### C — Subtitle sync: delay + speed + two-point (M)
- **Pure core (`:core:playback`, TDD):** change the active-cue lookup to `effectiveTime = (positionMs * rate).toLong() + offsetMs`; add `twoPointSync(orig1, want1, orig2, want2): SyncResult(rate, offset)` (linear fit: `rate = (want2-want1)/(orig2-orig1)`, `offset = want1 - orig1*rate`; guard `orig2==orig1`). Add `DEFAULT_SUBTITLE_RATE = 1.0`. Tests: rate scaling, offset+rate combined, two-point fit (incl. drift example), degenerate inputs.
- **Persistence:** add a per-file `subtitleRate` (Float, default 1.0) mirroring the existing `subtitleOffsetMs` plumbing exactly (entity column, `ResolvedStartSettings`, `persistSubtitle`/restore, VM). Forward-compatible nullable column; no destructive migration (Room: add nullable column / bump version with auto-migration or `fallbackToDestructiveMigration` is NOT used — add a proper migration or a new nullable field consistent with how `subtitleOffsetMs` was added).
- **UI (CC menu / a "Subtitle sync" sheet):** ① Delay ±50 ms (exists) · ② Speed/rate ± steppers (e.g. ±0.001 fine, ±0.01 coarse, or FPS-style presets 23.976↔25) with the current value shown · ③ **Precise sync (two-point):** guided flow — "play to the first line, tap *Mark line 1*; play to a later line, tap *Mark line 2*" → captures each line's original cue start + the desired (current playback) time → computes rate+offset, applies, persists. Live on-screen preview throughout. Applies to external/downloaded subs only (embedded engine-rendered tracks are not re-timeable — inherent).

### B — OpenSubtitles search & download (L — the flagship)
- **Manifest:** add `INTERNET` permission (adds the app's first network access, for OpenSubtitles — the app is private / self-distributed).
- **Pure core (`:core:playback` or a new pure file, TDD):** `SubtitleSearchResult` model (language, downloads, rating, fromTrusted, machineTranslated, release, fileId, fileName, hashMatch) + **`rankSubtitleResults(results, favoriteLanguages = [tr, en])`** — pin favorite languages to top, then sort by hashMatch, from_trusted, download_count, ratings; demote machine/AI-translated. Plus the **OSDb hash arithmetic** as a pure function over two 64 KiB byte arrays + filesize (the file IO that feeds it stays in `:app`). All TDD with the golden hash vector + ranking cases.
- **`:app` — credentials:** `OpenSubtitlesCredentials` in DataStore (api key, username; store the session token + base_url; never persist the password). A Settings → OpenSubtitles section: Api-Key field, username, password, "Log in" (calls `/login`, shows quota/level), "Log out", with a link + short instructions for registering a free API Consumer.
- **`:app` — client:** `OpenSubtitlesClient` (OkHttp + kotlinx.serialization): `login`, `search(hash?, query?, languages)`, `download(fileId) → bytes`, with the mandatory headers (incl. `User-Agent = "VideoPlayer v$versionName"`), `base_url` switching, token caching + 401-refresh, 429/5xx backoff, 406 quota surfacing. Unit-tested via MockWebServer.
- **`:app` — movie-hash:** `MovieHasher` computes the OSDb hash from the current video's content URI (per the storage decision); falls back to filename query.
- **`:app` — UI:** from the player's CC menu, a **"Search online (OpenSubtitles)"** entry → a results sheet (auto-runs a hash+filename search in the user's favorite languages, ranked; shows language flag/name, release, downloads/rating, trusted/MT badges, "exact match" for hashMatch) → one-tap download → saves the `.srt` (storage decision) → auto-selects it as the external subtitle (renders via the existing `CueOverlay`, immediately adjustable with feature C) → persists per-file. Quota/remaining shown; clear messages for not-logged-in / quota-exhausted / offline.
- **Favorite languages:** default `[tr, en]` (per the original star-feature intent); a Settings field to edit later (the value flows into `languages=` and `rankSubtitleResults`).

## Module boundaries & data model
- `:core:*` stays pure: new pure logic = sync math (`twoPointSync`, rate-aware cue lookup), result ranking, OSDb hash arithmetic. No Android, no OkHttp, no Compose in `:core`.
- `:app` owns: OkHttp client, DataStore credentials, file IO (hash + save), Settings UI, search/results UI, intent handling.
- Data model additions (all additive/forward-compatible, matching how prior reserved columns were added): `subtitleRate` per-file; OpenSubtitles credentials/token in DataStore (separate from the Room playback memory).

## Testing strategy
- **TDD (pure, JVM):** two-point sync + rate cue lookup; result ranking; OSDb hash arithmetic (golden vector); any token/encode helpers. Red→green→commit.
- **Unit (`:app`, JVM):** `OpenSubtitlesClient` against **MockWebServer** (login/search/download happy paths + 401 refresh + 406 quota + 429 backoff + bad-UA 403).
- **Device-verify (videoplayer AVD + real phone):** per feature — A: tap-to-pause fade; D: open a video from a file manager / `adb` VIEW intent; C: load an external sub, apply delay+speed, run two-point, confirm timing + persistence; B: log in, search (hash + name), download, render, sync, per-file restore, quota/error messaging. Watch `adb logcat` for crashes.

## Build & release sequencing
Each phase: branch → TDD → device-verify on `videoplayer` AVD → final review (most capable model) → merge ff to main → bump `versionCode`/`versionName` → signed `assembleRelease` → `gh release create` (Obtainium auto-updates).

| Release | versionCode | Contents |
|---|---|---|
| **v1.1.0** | 2 | A (controls fade fix) + D (open-with file-manager integration) |
| **v1.2.0** | 3 | C (subtitle sync: delay + speed + two-point precise) |
| **v1.3.0** | 4 | B (OpenSubtitles search/download + Settings login) |
| *future* | — | libmpv/libass: styled ASS + PGS embedded subs (separate milestone) |

## Risks & mitigations
- **OpenSubtitles API drift / quota:** read quota + base_url live; 401-refresh; never hardcode; surface clear quota/login messages. Mitigate token rate-limit by caching.
- **minSdk-24 TLS:** OkHttp 4.12.0 handles it (reason for the version pin).
- **Movie-hash on non-seekable URIs:** filename-search fallback.
- **Open-with coverage:** 3 intent-filters cover all mainstream file managers; opaque-octet-stream minority documented, no `*/*`.
- **Room migration for `subtitleRate`:** add as a nullable/defaulted column with a proper migration mirroring how `subtitleOffsetMs`/`orientation` reserved columns were introduced — no destructive migration, no data loss.
