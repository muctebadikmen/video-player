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
