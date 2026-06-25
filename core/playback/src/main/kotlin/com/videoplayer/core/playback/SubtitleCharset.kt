// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decode subtitle bytes to text, tolerant of the common Turkish legacy encodings.
 * Order: explicit BOM (UTF-8/16) -> strict UTF-8 -> Windows-1254 fallback.
 *
 * Rationale: a genuine UTF-8 file decodes cleanly under the strict attempt and is kept
 * verbatim. A Windows-1254 / ISO-8859-9 file almost always contains a byte sequence that
 * is invalid UTF-8 (Turkish high bytes are not valid UTF-8 continuations), so the strict
 * decode reports failure and we fall back to windows-1254 — which maps all 256 bytes and
 * never fails, so it is a safe terminal branch that also handles pure ASCII.
 */
fun decodeSubtitleBytes(bytes: ByteArray): String {
    bomCharset(bytes)?.let { (cs, offset) ->
        return String(bytes, offset, bytes.size - offset, cs)
    }
    decodeStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
    return String(bytes, turkishCharset())
}

/** Decode subtitle bytes (see [decodeSubtitleBytes]) and parse them into cues. */
fun parseSubtitleBytes(bytes: ByteArray): List<SubtitleCue> =
    parseSubtitles(decodeSubtitleBytes(bytes))

/**
 * windows-1254 is a superset of ISO-8859-9 for the Turkish letters we care about and maps
 * every one of its 256 bytes, so it never throws. Fall back to ISO-8859-9 only on the
 * (practically impossible) chance the platform lacks the windows-1254 alias.
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
