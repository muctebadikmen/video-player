// SPDX-License-Identifier: GPL-3.0-or-later
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

    @Test fun `subtitleMemoryToken encodes embedded, ext, and off`() {
        assertThat(subtitleMemoryToken("text:0:1", null)).isEqualTo("embedded:text:0:1")
        assertThat(subtitleMemoryToken(null, "content://x")).isEqualTo("ext:content://x")
        assertThat(subtitleMemoryToken(null, null)).isNull()
        // Embedded wins if both are somehow set (paths are mutually exclusive in practice).
        assertThat(subtitleMemoryToken("text:0:1", "content://x")).isEqualTo("embedded:text:0:1")
    }

    @Test fun `parseSubtitleToken decodes tokens and treats junk as Off`() {
        assertThat(parseSubtitleToken(null)).isEqualTo(SubtitleSelection.Off)
        assertThat(parseSubtitleToken("off")).isEqualTo(SubtitleSelection.Off)
        assertThat(parseSubtitleToken("embedded:text:0:1")).isEqualTo(SubtitleSelection.Embedded("text:0:1"))
        assertThat(parseSubtitleToken("ext:content://x")).isEqualTo(SubtitleSelection.External("content://x"))
        assertThat(parseSubtitleToken("garbage")).isEqualTo(SubtitleSelection.Off)
    }

    @Test fun `activeCueText with rate scales the lookup time`() {
        // A cue at [10000,11000). At rate 2.0 the lookup time is doubled, so real position 5000
        // maps to effective 10000 -> the cue is active; 4400 maps to 8800 -> nothing; 5500 -> 11000 (exclusive end) -> nothing.
        val cues = listOf(SubtitleCue(10_000, 11_000, "x"))
        assertThat(activeCueText(cues, positionMs = 5000, offsetMs = 0, rate = 2.0)).isEqualTo("x")
        assertThat(activeCueText(cues, positionMs = 4400, offsetMs = 0, rate = 2.0)).isNull()
        assertThat(activeCueText(cues, positionMs = 5500, offsetMs = 0, rate = 2.0)).isNull() // 5500*2=11000, exclusive
    }

    @Test fun `activeCueText default rate matches the legacy three-arg behavior`() {
        val cues = listOf(SubtitleCue(1000, 2000, "a"))
        // No rate arg => DEFAULT_SUBTITLE_RATE (1.0): identical to the offset-only lookup.
        assertThat(activeCueText(cues, 1500, 0)).isEqualTo("a")
        assertThat(activeCueText(cues, 1500, 0, DEFAULT_SUBTITLE_RATE)).isEqualTo("a")
        assertThat(DEFAULT_SUBTITLE_RATE).isEqualTo(1.0)
    }

    @Test fun `activeCueText combines offset and rate`() {
        // Cue [10000,12000). rate 1.0 + offset 600: real 9500 -> effective 9500+600=10100 -> active.
        val cues = listOf(SubtitleCue(10_000, 12_000, "c"))
        assertThat(activeCueText(cues, positionMs = 9500, offsetMs = 600, rate = 1.0)).isEqualTo("c")
        // rate 1.1 + offset -500: real 10000 -> effective (10000*1.1)=11000 -500 = 10500 -> active.
        assertThat(activeCueText(cues, positionMs = 10_000, offsetMs = -500, rate = 1.1)).isEqualTo("c")
    }

    @Test fun `twoPointSync fits rate and offset from two points`() {
        // Subtitle file runs 4 percent slow: each real second should map to 1.04 subtitle-seconds.
        // Point 1: the cue whose file-start is 60000 is heard at real time 57692 (=60000/1.04).
        // Point 2: the cue whose file-start is 600000 is heard at real time 576923 (=600000/1.04).
        // Fitting want=rate*orig+offset over (orig=real, want=file): rate ~= 1.04, offset ~= 0.
        val r = twoPointSync(orig1 = 57_692, want1 = 60_000, orig2 = 576_923, want2 = 600_000)
        assertThat(r.rate).isWithin(1e-3).of(1.04)
        assertThat(r.offset).isWithin(2L).of(0L)
    }

    @Test fun `twoPointSync recovers a pure constant delay as rate one`() {
        // Both lines are simply 3000ms late (constant offset, no drift):
        // file-start 5000 heard at 8000, file-start 20000 heard at 23000.
        // want=rate*orig+offset => rate=1.0, offset = 5000 - 8000 = -3000.
        val r = twoPointSync(orig1 = 8000, want1 = 5000, orig2 = 23_000, want2 = 20_000)
        assertThat(r.rate).isWithin(1e-9).of(1.0)
        assertThat(r.offset).isEqualTo(-3000L)
    }

    @Test fun `twoPointSync returns identity when the two original times are equal`() {
        val r = twoPointSync(orig1 = 5000, want1 = 7000, orig2 = 5000, want2 = 9000)
        assertThat(r.rate).isEqualTo(DEFAULT_SUBTITLE_RATE)
        assertThat(r.offset).isEqualTo(0L)
    }

    @Test fun `twoPointSync result applied via activeCueText lands the drifted cue`() {
        // End-to-end: a file drifting 4% slow. Apply the fit, then look up the second line at its
        // real heard time and confirm the cue is active.
        val r = twoPointSync(orig1 = 57_692, want1 = 60_000, orig2 = 576_923, want2 = 600_000)
        val cues = listOf(SubtitleCue(600_000, 602_000, "late line"))
        assertThat(activeCueText(cues, positionMs = 576_923, offsetMs = r.offset, rate = r.rate))
            .isEqualTo("late line")
    }
}