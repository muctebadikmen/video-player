<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Fresh-session kickoff prompt — build v1.1 → v1.2 → v1.3

Paste everything in the fenced block below into a fresh Claude Code chat opened in this repo.

---

```
Continue the video-player project (Android, Kotlin + Compose + Media3). Build the next THREE feature releases — v1.1.0 → v1.2.0 → v1.3.0 — end-to-end and autonomously, shipping each as a signed, self-distributed release. Work as an ORCHESTRATOR / project manager: push ALL real work into fresh-context subagents and keep your own context clean. The plans are already written and committed — execute them, don't re-plan from scratch.

FIRST, load full context (read these before acting):
- CLAUDE.md — operating model (orchestrate; Superpowers skills; autopilot rules).
- Memory: /Users/mustafa/.claude/projects/-Users-mustafa-Desktop-Projects-mobil-uygulama-video-player/memory/phase0-foundation-state.md — current build state, toolchain quirks, the `videoplayer` AVD, device-test gotchas, that v1.0.0 is released + self-distributed, and the v1.x roadmap.
- DESIGN SPEC (decisions are LOCKED — do not re-litigate): docs/superpowers/specs/2026-06-19-subtitle-power-and-player-polish-design.md — approved design + all resolved technical decisions (OkHttp 4.12.0 + kotlinx-serialization-json 1.7.3 networking, the full OpenSubtitles API contract, app-private file:// subtitle storage, OSDb movie-hash from a content URI, the open-with intent-filters).
- THE THREE IMPLEMENTATION PLANS — execute IN THIS ORDER, one release at a time:
  1. docs/superpowers/plans/2026-06-19-v1.1-controls-fade-and-open-with.md  (A: controls fade fix + D: open-with file-manager integration) → release v1.1.0
  2. docs/superpowers/plans/2026-06-19-v1.2-subtitle-sync.md                 (C: subtitle sync — delay + speed/rate + two-point precise) → release v1.2.0
  3. docs/superpowers/plans/2026-06-19-v1.3-opensubtitles.md                 (B: OpenSubtitles search & download + Settings login) → release v1.3.0
- Ledger: .git/sdd/progress.md — full task history; append progress as you complete each task/release.
- Recent `git log --oneline -20`.

CURRENT STATE: origin/main holds the spec + all three plans. v1.0.0 is built + signed (tag v1.0.0): the GitHub repo (muctebadikmen/video-player) is PRIVATE. The app is distributed PRIVATELY — build a signed APK and install it directly on the owner's device (or via Obtainium pointed at the private repo with a GitHub token). F-Droid was withdrawn; do not interact with F-Droid. Full JVM test suite + clean signed assembleRelease are green. Signing key is at ~/keystores/videoplayer-release.jks, wired via the gitignored keystore.properties at the repo root.

GOAL — deliver v1.1 → v1.2 → v1.3 in order; each FULLY finished, device-verified, merged, pushed, and released. For EACH release:
  1. Branch off main.
  2. Execute its plan task-by-task with superpowers:subagent-driven-development — a fresh subagent per task, TDD (red→green→commit) on all pure logic, two-stage per-task review.
  3. Device-verify the whole release on the `videoplayer` AVD (see the plan's Device verification task). Confirm no crashes via `adb logcat`.
  4. Final whole-branch review on the most capable model; fix any Critical/Important findings, record Minors.
  5. finishing-a-development-branch: merge ff to main, push to origin/main.
  6. Release: bump versionCode + versionName in app/build.gradle.kts (v1.1.0=code 2, v1.2.0=code 3, v1.3.0=code 4), signed `assembleRelease`, then hand the signed APK to the owner to install — rename the asset to VideoPlayer-X.Y.Z.apk (from app/build/outputs/apk/release/app-release.apk). Optionally create a release on the PRIVATE repo with `gh release create vX.Y.Z --repo muctebadikmen/video-player --title "Video Player X.Y.Z" --notes-file <notes> VideoPlayer-X.Y.Z.apk` if the owner uses Obtainium-with-token. No public release, no F-Droid.
  7. Update .git/sdd/progress.md and the phase0-foundation-state memory.
  Then proceed to the next release.

OPERATING MODEL (per CLAUDE.md, this repo's rhythm):
- Orchestrate — subagents do all the work; summarize their results back, don't inline their raw work. Use the Superpowers skills (subagent-driven-development, test-driven-development, verification-before-completion, requesting-code-review, finishing-a-development-branch). Use using-git-worktrees if you run parallel subagents that mutate files.
- Commit after every green step. main stays releasable. TDD all pure :core logic.
- Decisions are front-loaded in the spec — proceed; don't re-ask. If a NEW genuine product/UX fork appears, pick the most defensible default consistent with the spec + MASTER_PROMPT.md, note it, and proceed.

BUILD & DEVICE SPECIFICS (these will bite a fresh session):
- System JDK is 24 and breaks AGP — prefix EVERY gradle call: export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home". Wrapper only (./gradlew). `./gradlew test`, `:app:assembleDebug`, `:app:assembleRelease`. minSdk 24 / targetSdk 35. Keep :core:model and :core:playback PURE (no android.*/Compose/Media3/OkHttp). SPDX header on every new .kt.
- Emulator: AVD `videoplayer` (API 35). Boot: ~/Library/Android/sdk/emulator/emulator -avd videoplayer -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -no-audio & then `adb wait-for-device`. adb at /opt/homebrew/bin/adb. Test clips at /sdcard/Movies/VPTest/ (clip0/1, clipA/B/C, clipEmbed.mkv + clipA.srt/clipEmbed.srt; re-create via ffmpeg if the AVD was wiped — recipe in the memory file).
- Device-test gotchas: (1) screenshots are 1080×2400 — resize `sips -Z 1600 in.png --out out.png`, then ×1.5 the coords. (2) a SINGLE tap toggles controls; to pause, tap to show controls then tap the VISIBLE center button. (3) the CC subtitle DropdownMenu is flaky under scripted taps — verify subtitle state via `adb shell run-as com.videoplayer.app sqlite3 databases/video_player.db "SELECT ..."` + on-screen cue rendering, not just the menu. (4) `pm clear com.videoplayer.app` wipes per-file memory (re-grant READ_MEDIA_VIDEO + POST_NOTIFICATIONS). (5) playback auto-advances through the folder playlist.
- Release/distribution: signing key ~/keystores/videoplayer-release.jks via gitignored keystore.properties (already set up). gh authed as muctebadikmen (the repo is PRIVATE). Each release: version bump → signed assembleRelease → hand the signed APK to the owner to install directly (or, optionally, `gh release create` on the private repo for Obtainium-with-token).

USER-SETUP PREREQUISITE (blocks ONLY v1.3 device-verify — not v1.1/v1.2): the OpenSubtitles feature needs the USER's own free OpenSubtitles account + a free "API Consumer" key (opensubtitles.com → Profile → API Consumers → New Consumer), which they enter in the app's Settings → OpenSubtitles. The app embeds NO credentials. Build v1.1 and v1.2 first. All of v1.3's code, unit tests (the OpenSubtitles client is tested against OkHttp MockWebServer), and builds proceed WITHOUT the account. Only when you reach v1.3's on-device login/search/download verification do you need it — at that point, if the user hasn't supplied an account+key, STOP and ask for it.

AUTONOMY — proceed vs stop:
- Keep going WITHOUT asking for: all code/tests/refactors within the plans, builds, device-verify, spawning subagents, checkpoint commits, merges to main, pushes to origin/main, version bumps, signed release builds, AND building + handing over signed APKs (this is the routine path — NOT a hard stop).
- STOP and ask ONLY for: (1) the OpenSubtitles account/API key at v1.3's device-verify; (2) adding ANY dependency beyond the spec-approved OkHttp 4.12.0 + kotlinx-serialization-json 1.7.3 + MockWebServer(test); (3) the same error surviving 2 fix attempts — explain rather than thrash; (4) anything genuinely irreversible/outward-facing (force-push, deleting work you didn't create).
- Do NOT add the INTERNET permission until v1.3 (it belongs to feature B); v1.1 and v1.2 must stay network-free.

Don't stop until v1.1.0, v1.2.0, and v1.3.0 are each built, device-verified, merged, pushed, and a signed APK handed over — except the single human dependency (the OpenSubtitles account for v1.3's live verification). Then give a concise summary of all three releases and where each signed APK is (ready to install directly, or on the private repo for Obtainium-with-token).
```

---

## Notes for you (the human), not part of the prompt
- The fresh chat will run for a while (v1.3/OpenSubtitles is the big one). It will pause once, at v1.3's on-device test, to ask for your OpenSubtitles account + API-Consumer key — have those ready (free: opensubtitles.com → register → Profile → API Consumers → New Consumer).
- Each release produces a signed APK to install directly on your phone (or, if you use Obtainium pointed at the private repo with a GitHub token, it can auto-update from there); nothing else needed from you between releases.
- If you'd rather ship fewer/bigger releases, say so in the fresh chat — the plans are independent and can be combined.
