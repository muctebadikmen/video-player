// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decode subtitle bytes to text, tolerant of the common Turkish legacy encodings.
 * Order: explicit BOM (UTF-8/16) -> strict UTF-8 -> Windows-1254 (with ISO-8859-9 safety net).
 *
 * Rationale: a genuine UTF-8 file decodes cleanly under the strict attempt and is kept
 * verbatim. A Windows-1254 / ISO-8859-9 file almost always contains a byte sequence that
 * is invalid UTF-8 (Turkish high bytes are not valid UTF-8 continuations), so the strict
 * decode reports failure and we fall back to windows-1254 — the right primary Turkish decode
 * because it maps 0x80–0x9F to real glyphs (em-dash, smart quotes) that appear in subtitles.
 *
 * windows-1254 leaves six bytes undefined (0x81, 0x8D–0x8F, 0x90, 0x9D), which would decode to
 * U+FFFD. ISO-8859-9 maps all 256 bytes losslessly, so it is the safety net: if the windows-1254
 * decode produced any replacement char, we re-decode with ISO-8859-9 and return that instead,
 * guaranteeing the output never contains U+FFFD.
 *
 * Out of scope: BOM-less UTF-16 is not detected — such bytes are treated as legacy/Turkish, not
 * decoded as UTF-16 (only BOM-prefixed UTF-16 is recognised, via [bomCharset]).
 */
fun decodeSubtitleBytes(bytes: ByteArray): String {
    bomCharset(bytes)?.let { (cs, offset) ->
        return String(bytes, offset, bytes.size - offset, cs)
    }
    decodeStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
    val primary = String(bytes, turkishCharset())
    // ISO-8859-9 maps every byte, so it can never emit U+FFFD; use it whenever windows-1254 did.
    return if (primary.contains('�')) String(bytes, Charset.forName("ISO-8859-9")) else primary
}

/** Decode subtitle bytes (see [decodeSubtitleBytes]) and parse them into cues. */
fun parseSubtitleBytes(bytes: ByteArray): List<SubtitleCue> =
    parseSubtitles(decodeSubtitleBytes(bytes))

/**
 * windows-1254 is a superset of ISO-8859-9 for the Turkish letters we care about and additionally
 * maps 0x80–0x9F to real glyphs (em-dash, smart quotes) that show up in subtitles, so it is the
 * preferred primary decode. It never throws (undefined bytes decode to U+FFFD rather than failing);
 * [decodeSubtitleBytes] applies an ISO-8859-9 safety net to eliminate any such replacement chars.
 * Fall back to ISO-8859-9 here only on the (practically impossible) chance the platform lacks the
 * windows-1254 alias.
 */
private fun turkishCharset(): Charset =
    runCatching { Charset.forName("windows-1254") }
        .getOrElse { Charset.forName("ISO-8859-9") }

/** Strictly decode [bytes] as [cs]; returns null if any byte is malformed/unmappable. */
private fun decodeStrict(bytes: ByteArray, cs: Charset): String? = runCatching {
    cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/** Charset and byte offset to skip if [b] starts with a UTF-8/UTF-16 BOM; null otherwise. */
private fun bomCharset(b: ByteArray): Pair<Charset, Int>? = when {
    b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
        StandardCharsets.UTF_8 to 3
    b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
        StandardCharsets.UTF_16LE to 2
    b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
        StandardCharsets.UTF_16BE to 2
    else -> null
}
