// SPDX-License-Identifier: GPL-3.0-or-later
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

/** The active subtitle selection, decoded from a per-file memory token. */
sealed interface SubtitleSelection {
    data object Off : SubtitleSelection
    data class Embedded(val id: String) : SubtitleSelection
    data class External(val uri: String) : SubtitleSelection
}

/**
 * Encode the active subtitle selection for per-file memory:
 * `"embedded:<id>"`, `"ext:<uri>"`, or null when nothing is selected.
 * Embedded takes precedence (the two paths are mutually exclusive at runtime).
 */
fun subtitleMemoryToken(embeddedTrackId: String?, externalUri: String?): String? = when {
    embeddedTrackId != null -> "embedded:$embeddedTrackId"
    externalUri != null -> "ext:$externalUri"
    else -> null
}

/** Decode a memory token (see [subtitleMemoryToken]); unrecognized input => [SubtitleSelection.Off]. */
fun parseSubtitleToken(token: String?): SubtitleSelection = when {
    token == null || token == "off" -> SubtitleSelection.Off
    token.startsWith("embedded:") -> SubtitleSelection.Embedded(token.removePrefix("embedded:"))
    token.startsWith("ext:") -> SubtitleSelection.External(token.removePrefix("ext:"))
    else -> SubtitleSelection.Off
}