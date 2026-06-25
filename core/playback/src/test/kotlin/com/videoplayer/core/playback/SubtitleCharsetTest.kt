// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class SubtitleCharsetTest {
    private val turkishSample = "Çocuğun ışığı söndü; şık güneş, öğün."
    private val turkishGlyphs = listOf('ç', 'ğ', 'ı', 'ş', 'ö', 'ü', 'İ')
    private val replacementChar = '�'

    /** A one-cue SRT carrying [text]; encoded in-test with a given charset so samples are explicit. */
    private fun srt(text: String): String =
        "1\n00:00:01,000 --> 00:00:04,000\n$text\n"

    @Test fun `windows-1254 srt decodes to correct Turkish glyphs`() {
        // Covers every required glyph: lowercase ç ğ ı ş ö ü and uppercase İ.
        val text = "İçtiğin çayın ışığı söndü; şık güneş, öğün."
        val bytes = srt(text).toByteArray(Charset.forName("windows-1254"))
        val cues = parseSubtitleBytes(bytes)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo(text)
        for (g in turkishGlyphs) {
            assertThat(cues[0].text).contains(g.toString())
        }
        assertThat(cues[0].text).doesNotContain(replacementChar.toString())
    }

    @Test fun `ISO-8859-9 srt decodes correctly via Turkish fallback`() {
        val bytes = srt(turkishSample).toByteArray(Charset.forName("ISO-8859-9"))
        val cues = parseSubtitleBytes(bytes)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo(turkishSample)
        assertThat(cues[0].text).doesNotContain(replacementChar.toString())
    }

    @Test fun `genuine UTF-8 Turkish is preserved and not re-mapped`() {
        val bytes = srt(turkishSample).toByteArray(StandardCharsets.UTF_8)
        val decoded = decodeSubtitleBytes(bytes)
        assertThat(decoded).contains(turkishSample)
        assertThat(decoded).doesNotContain(replacementChar.toString())
        val cues = parseSubtitleBytes(bytes)
        assertThat(cues[0].text).isEqualTo(turkishSample)
    }

    @Test fun `UTF-8 BOM is stripped from the first cue`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = srt("Merhaba").toByteArray(StandardCharsets.UTF_8)
        val cues = parseSubtitleBytes(bom + body)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo("Merhaba")
        assertThat(cues[0].text.first()).isNotEqualTo('﻿')
    }

    @Test fun `UTF-16LE with BOM decodes correctly`() {
        // String(bytes, UTF_16LE) drops the BOM; toByteArray(UTF_16LE) does not emit one,
        // so prepend the FF FE BOM explicitly to exercise the BOM-sniff branch.
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val body = srt(turkishSample).toByteArray(StandardCharsets.UTF_16LE)
        val cues = parseSubtitleBytes(bom + body)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo(turkishSample)
        assertThat(cues[0].text).doesNotContain(replacementChar.toString())
    }

    @Test fun `UTF-16BE with BOM decodes correctly`() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val body = srt(turkishSample).toByteArray(StandardCharsets.UTF_16BE)
        val cues = parseSubtitleBytes(bom + body)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo(turkishSample)
    }

    @Test fun `pure ASCII decodes identically`() {
        val text = "Hello world, plain ASCII."
        val bytes = srt(text).toByteArray(StandardCharsets.US_ASCII)
        val cues = parseSubtitleBytes(bytes)
        assertThat(cues).hasSize(1)
        assertThat(cues[0].text).isEqualTo(text)
    }

    @Test fun `a windows-1254 byte invalid as UTF-8 triggers the fallback`() {
        // 0xF0 is 'ğ' in windows-1254 but starts an invalid/truncated UTF-8 sequence here,
        // so strict UTF-8 must reject it and the Turkish fallback must win (not U+FFFD).
        val bytes = byteArrayOf(0xF0.toByte()) // 'ğ' in windows-1254
        val decoded = decodeSubtitleBytes(bytes)
        assertThat(decoded).isEqualTo("ğ")
        assertThat(decoded).doesNotContain(replacementChar.toString())
    }
}
