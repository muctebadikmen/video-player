# P1.G-1 — Subtitle pure core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Pure-Kotlin subtitle logic in `:core:playback`: SRT/VTT parsing, active-cue selection with timing offset, sibling-filename matching, the nudge constant/stepper, and the `TextTrackInfo` model — all under red-green unit tests.

**Architecture:** One cohesive `Subtitle.kt` in `:core:playback` (pure; no `android.*`/Compose/Media3). A tolerant line-based parser handles both SRT (`,` ms separator) and VTT (`.` ms separator, `WEBVTT` header, cue settings) via a shared engine.

**Tech Stack:** Kotlin, JUnit4 + Truth.

## Global Constraints

- Build with JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew`.
- `:core:*` MUST stay pure Kotlin — no `android.*`, Compose, or Media3 imports.
- Commit after every green step.
- Spec: `docs/superpowers/specs/2026-06-19-p1g-subtitles-design.md`.

---

### Task G1.1: SubtitleCue + TextTrackInfo + parser + helpers (TDD)

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/Subtitle.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/SubtitleTest.kt`

**Interfaces — Produces:**
- `data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)`
- `data class TextTrackInfo(val id: String, val label: String, val language: String? = null)`
- `fun parseSubtitles(content: String): List<SubtitleCue>` (tolerant; handles SRT + VTT)
- `fun parseSrt(content: String): List<SubtitleCue>` / `fun parseVtt(content: String): List<SubtitleCue>` (delegate to `parseSubtitles`)
- `fun activeCueText(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long): String?` (offset>0 ⇒ cues appear earlier)
- `fun findSiblingSubtitles(videoFileName: String, candidates: List<String>): List<String>`
- `const val SUBTITLE_NUDGE_MS: Long = 50` + `fun nudgeSubtitleOffset(currentMs: Long, deltaMs: Long): Long`

- [ ] **Step 1: Write the failing tests** (`SubtitleTest.kt`):

```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleTest {
    @Test fun `parses well-formed srt`() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n2\n00:00:05,500 --> 00:00:07,000\nSecond\nline\n"
        val cues = parseSrt(srt)
        assertThat(cues).hasSize(2)
        assertThat(cues[0]).isEqualTo(SubtitleCue(1000, 4000, "Hello world"))
        assertThat(cues[1]).isEqualTo(SubtitleCue(5500, 7000, "Second\nline"))
    }
    @Test fun `parses well-formed vtt with header and settings`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000 line:90%\nHi there\n\n01:02.500 --> 01:04.000\nNo hours\n"
        val cues = parseVtt(vtt)
        assertThat(cues).hasSize(2)
        assertThat(cues[0]).isEqualTo(SubtitleCue(1000, 4000, "Hi there"))
        assertThat(cues[1]).isEqualTo(SubtitleCue(62500, 64000, "No hours"))
    }
    @Test fun `tolerates malformed blocks and empty input`() {
        assertThat(parseSubtitles("")).isEmpty()
        assertThat(parseSubtitles("garbage\nno timings here\n")).isEmpty()
        val mixed = "1\nNOT A TIMING\nx\n\n2\n00:00:02,000 --> 00:00:03,000\nok\n"
        assertThat(parseSubtitles(mixed)).containsExactly(SubtitleCue(2000, 3000, "ok"))
    }
    @Test fun `activeCueText finds the cue at position`() {
        val cues = listOf(SubtitleCue(1000, 2000, "a"), SubtitleCue(3000, 4000, "b"))
        assertThat(activeCueText(cues, 1500, 0)).isEqualTo("a")
        assertThat(activeCueText(cues, 2500, 0)).isNull()
        assertThat(activeCueText(cues, 3000, 0)).isEqualTo("b")  // inclusive start
        assertThat(activeCueText(cues, 4000, 0)).isNull()        // exclusive end
    }
    @Test fun `activeCueText applies positive offset to show cues earlier`() {
        val cues = listOf(SubtitleCue(3000, 4000, "b"))
        // at real position 2500, +600ms offset => look up at 3100 => cue 'b' shows early
        assertThat(activeCueText(cues, 2500, 600)).isEqualTo("b")
        // negative offset delays
        assertThat(activeCueText(cues, 3000, -600)).isNull()
    }
    @Test fun `findSiblingSubtitles matches base name, lang infix, and ignores wrong ext`() {
        val candidates = listOf("movie.srt", "movie.en.srt", "movie.tr.vtt", "movie.mp4", "other.srt", "movie.txt")
        val result = findSiblingSubtitles("movie.mkv", candidates)
        assertThat(result).containsExactly("movie.srt", "movie.en.srt", "movie.tr.vtt")
    }
    @Test fun `findSiblingSubtitles is case-insensitive and handles no match`() {
        assertThat(findSiblingSubtitles("Movie.MKV", listOf("MOVIE.SRT"))).containsExactly("MOVIE.SRT")
        assertThat(findSiblingSubtitles("clip.mp4", listOf("a.srt", "b.vtt"))).isEmpty()
    }
    @Test fun `nudgeSubtitleOffset steps and clamps`() {
        assertThat(nudgeSubtitleOffset(0, SUBTITLE_NUDGE_MS)).isEqualTo(50)
        assertThat(nudgeSubtitleOffset(0, -SUBTITLE_NUDGE_MS)).isEqualTo(-50)
        assertThat(nudgeSubtitleOffset(600_000, 50)).isEqualTo(600_000)     // clamp max
        assertThat(nudgeSubtitleOffset(-600_000, -50)).isEqualTo(-600_000)  // clamp min
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:playback:test`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement `Subtitle.kt`**

```kotlin
package com.videoplayer.core.playback

/** One subtitle cue: [text] is shown from [startMs] (inclusive) to [endMs] (exclusive). */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** A selectable embedded text (subtitle) track exposed by the engine. */
data class TextTrackInfo(val id: String, val label: String, val language: String? = null)

/** Largest subtitle offset the nudge allows in either direction (10 minutes). */
private const val MAX_SUBTITLE_OFFSET_MS = 600_000L

/** One nudge step for the subtitle sync control. */
const val SUBTITLE_NUDGE_MS: Long = 50

/** Step the offset by [deltaMs], clamped to ±[MAX_SUBTITLE_OFFSET_MS]. */
fun nudgeSubtitleOffset(currentMs: Long, deltaMs: Long): Long =
    (currentMs + deltaMs).coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)

/** Parse SRT content. */
fun parseSrt(content: String): List<SubtitleCue> = parseSubtitles(content)

/** Parse WebVTT content. */
fun parseVtt(content: String): List<SubtitleCue> = parseSubtitles(content)

/**
 * Tolerant line-based parser for SRT and VTT. A cue is any line containing "-->"
 * with two parseable timestamps, followed by its (non-blank) text lines. Malformed
 * blocks are skipped. Timestamps accept "," or "." for milliseconds and an optional
 * hours field; cue settings after the end time are ignored.
 */
fun parseSubtitles(content: String): List<SubtitleCue> {
    val cues = mutableListOf<SubtitleCue>()
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    while (i < lines.size) {
        val arrowIdx = lines[i].indexOf("-->")
        if (arrowIdx >= 0) {
            val startTok = lines[i].substring(0, arrowIdx)
            val rest = lines[i].substring(arrowIdx + 3).trim()
            val endTok = rest.split(Regex("\\s+")).firstOrNull().orEmpty()
            val start = parseTimestampMs(startTok)
            val end = parseTimestampMs(endTok)
            if (start != null && end != null) {
                i++
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i].trim())
                    i++
                }
                cues.add(SubtitleCue(start, end, sb.toString()))
                continue
            }
        }
        i++
    }
    return cues
}

private fun parseTimestampMs(token: String): Long? {
    val t = token.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val colon = t.split(':')
    return try {
        val h: Long
        val m: Long
        val secMs: String
        when (colon.size) {
            3 -> { h = colon[0].toLong(); m = colon[1].toLong(); secMs = colon[2] }
            2 -> { h = 0; m = colon[0].toLong(); secMs = colon[1] }
            else -> return null
        }
        val sec = secMs.substringBefore('.').toLong()
        val ms = secMs.substringAfter('.', "0").padEnd(3, '0').take(3).toLong()
        ((h * 3600 + m * 60 + sec) * 1000) + ms
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Text of the cue active at [positionMs] shifted by [offsetMs] (offset>0 makes cues
 * appear earlier). Returns null when no cue covers that time. Linear scan — cue lists
 * are modest and this runs on the position tick; fine for V1.
 */
fun activeCueText(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long): String? {
    val t = positionMs + offsetMs
    return cues.firstOrNull { t >= it.startMs && t < it.endMs }?.text
}

/**
 * Sibling subtitle file names for [videoFileName]: same base name, extension .srt/.vtt,
 * with an optional ".<lang>" infix (e.g. movie.mkv → movie.srt, movie.en.srt). Case-insensitive.
 */
fun findSiblingSubtitles(videoFileName: String, candidates: List<String>): List<String> {
    val base = videoFileName.substringBeforeLast('.').lowercase()
    val exts = setOf("srt", "vtt")
    return candidates.filter { cand ->
        val ext = cand.substringAfterLast('.', "").lowercase()
        if (ext !in exts) return@filter false
        val candBase = cand.substringBeforeLast('.').lowercase()
        candBase == base || candBase.startsWith("$base.")
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:playback:test` (JAVA_HOME). Expected: PASS (all SubtitleTest + existing tests green).

- [ ] **Step 5: Commit**

```bash
git add core/playback && git commit -m "feat(core): subtitle parsing, active-cue, sibling matching, and offset nudge with tests"
```

---

## Self-Review

- **Spec coverage:** G-1 section → SubtitleCue, TextTrackInfo, parsers, activeCueText, findSiblingSubtitles, nudge. All present.
- **Placeholder scan:** concrete code throughout.
- **Type consistency:** names match the spec's `:core:playback` interface block (parseSrt/parseVtt/parseSubtitles, activeCueText, findSiblingSubtitles, SUBTITLE_NUDGE_MS, nudgeSubtitleOffset, SubtitleCue, TextTrackInfo). `parseSubtitles` takes only `content` (the tolerant engine handles both formats; the spec's `isVtt` param is unnecessary — documented here as the refinement).
- **Purity:** no Android/Compose/Media3 imports.
