# CLAUDE.md

Operating guidelines for this codebase. Biases toward correctness and reversibility over raw speed. For trivial tasks, use judgment and skip the ceremony.

---

## Operating Model — orchestrate, don't do it all yourself

**This session is the orchestrator. Keep its context clean. Push the actual work into fresh-context subagents.** This is what lets a run go for hours without quality decay.

- **Use the Superpowers skills.** They self-trigger from their descriptions — let them. Don't re-implement their logic by hand. The loop they enforce: `brainstorming` → `writing-plans` → `test-driven-development` → execute → `verification-before-completion` → `finishing-a-development-branch`.
- **Delegate execution to subagents.** For multi-step plans use `subagent-driven-development`; for 2+ independent tasks use `dispatching-parallel-agents`. Each subagent starts clean — summarize their results back here, don't inline their raw work into this context.
- **Workflows for heavy fan-out only.** Reach for the Workflow tool when work is genuinely parallel/independent (research sweeps, multi-file audits, broad refactors). It costs a lot of tokens — don't use it where a couple of subagents suffice.
- **Isolate parallel work** with `using-git-worktrees` so concurrent subagents never collide.

## Autopilot — proceed vs. stop

Designed to run end-to-end with minimal interruption. The rule:

**Front-load decisions, then run.** Ask everything you need during `brainstorming` / `writing-plans`, get one sign-off on the plan, then execute autonomously through to a verified, committed result.

**Keep going without asking** for: writing code, tests, refactors within scope, running tests/builds/linters, reading files, creating files you own, checkpoint commits, spawning subagents.

**Stop and ask — even on bypass — for:**
1. Irreversible or outward-facing actions: `git push --force`, deploy, publishing, deleting files you did not create, DB migrations / schema drops.
2. Anything that spends money, sends an external message/email, or hits a production system.
3. Installing new dependencies or adding a new service/framework not already chosen.
4. A genuine product/UX fork where either choice is defensible — present options, don't pick silently.
5. The same error surviving 2 fix attempts — stop and explain rather than thrash.

When you do stop, ask one tight, batched question and keep moving on everything not blocked by it.

## Safety net (what makes autopilot safe)

- **Commit after every green step.** Small, focused, descriptive commits. This is the undo button — never skip it on a long run.
- **Tests are the contract.** TDD: failing test first, then code. Don't claim done without running the verification commands and seeing them pass.
- **Reversibility first.** Prefer additive changes; never overwrite or delete non-trivial work without it being committed or confirmed.

---

## Project Overview

- **Name:** video-player — open-source Android video player. Full V1 spec: `MASTER_PROMPT.md` (source of truth).
- **What it does:** Ad-free, account-free, UX-first Android player. "Open and play, never think." VLC's power without the clutter; MX Player's gestures without the ads.
- **Tech stack:** Native Android — Kotlin + Jetpack Compose. Hybrid playback: Media3/ExoPlayer (default) + libmpv (power/fallback, Phase 2) behind one `PlaybackEngine` interface. GPLv3, F-Droid first. iOS later via KMP (core kept UI-agnostic).
- **Status:** **Phase 0 complete** (tag `phase-0`): buildable app, MediaStore library browser, Media3 playback behind the engine seam, pure-Kotlin core under test. Next: **Phase 1** (lean MVP → first F-Droid release). Roadmap & per-task plan: `docs/superpowers/plans/2026-06-17-phase0-phase1-foundation-mvp.md`.

## Project Structure

Multi-module Gradle (Kotlin DSL + version catalog `gradle/libs.versions.toml`):
- `:core:model` — pure Kotlin (no Android): `MediaItem`, `MediaFolder`, `MediaRepository`, `groupIntoFolders`, `formatDuration`. JVM-unit-tested.
- `:core:playback` — pure Kotlin: `PlaybackEngine` interface, `PlaybackState`/`PlayerStatus`/`EngineType`, `FakePlaybackEngine`. JVM-unit-tested.
- `:app` — Android/Compose: `Media3PlaybackEngine` (impl of the interface), `MediaStoreRepository`, `theme/` (Material You + true-black AMOLED), `library/` (screen + ViewModel), `player/` (PlayerScreen), `VideoPlayerApp` nav, `MainActivity`.
- **Rule:** `:core:*` must stay free of `android.*` / `androidx.compose.*` / `media3` imports. Android/Media3 lives only in `:app`.

## Key Commands

Builds require JDK 21 (system JDK is 24, which breaks AGP). Prefix every Gradle call:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test                  # all JVM unit tests (core + app)
./gradlew :core:model:test      # one module's tests
./gradlew :app:assembleDebug    # build debug APK -> app/build/outputs/apk/debug/
./gradlew :app:installDebug     # install on running emulator/device
```
Emulator smoke: AVD `kuran_test` (`~/Library/Android/sdk/emulator/emulator -avd kuran_test`); `adb` for install/launch/screencap.

---

## Core Principles

**1. Think before coding.** State assumptions; if uncertain, ask. Surface tradeoffs and simpler approaches instead of silently choosing. Name what's confusing rather than guessing.

**2. Simplicity first.** Minimum code that solves the problem. No speculative features, abstractions for single-use code, or unrequested configurability. If 200 lines could be 50, rewrite. Test: "Would a senior engineer call this overcomplicated?"

**3. Surgical changes.** Touch only what the request requires; every changed line should trace to it. Match existing style even if you'd do it differently — read code before changing it. Remove imports/vars *your* change orphaned; leave pre-existing dead code (mention it, don't delete it).

**4. Goal-driven.** Turn vague asks into verifiable goals ("add validation" → "tests for invalid inputs, then make them pass"). State a brief plan with a verify-check per step. Verify before reporting done.

## Code Style

Clear descriptive names · small focused files · reuse existing patterns before inventing · comments only where logic is non-obvious · imports/exports consistent with the codebase · UI responsive and mobile-friendly (this is a mobile app).

## Never

1. Hardcode secrets (keys, passwords, tokens) — read `.env` / `.env.local` first.
2. Push, deploy, or force-push without explicit permission.
3. Delete/overwrite files you didn't create, or work outside the request's scope, without confirming.
4. Install packages or add services without asking.
5. Rewrite working code to "clean it up" unless asked.
6. Assume the schema, env, or API shape — read it first.

## When stuck

Read the terminal and browser console before changing code. If an error survives 2 attempts, stop and explain. For large tasks, confirm the plan once, then run.
