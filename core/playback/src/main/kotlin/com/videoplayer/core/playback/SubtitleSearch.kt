// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/** One OpenSubtitles search hit, normalized to the fields the UI + ranking need. */
data class SubtitleSearchResult(
    val fileId: Long,
    val fileName: String,
    val language: String,
    val release: String,
    val downloadCount: Int,
    val rating: Double,
    val fromTrusted: Boolean,
    val machineTranslated: Boolean,
    val hashMatch: Boolean,
)

/**
 * Rank results for display. Favorite languages pin to the top in the order given (a result whose
 * language is not in the list ranks after every favorite-language result). Within one language
 * tier, order by: exact hash match first, then trusted, then human over machine-translated, then
 * higher download count, then higher rating. Deterministic and pure.
 */
fun rankSubtitleResults(
    results: List<SubtitleSearchResult>,
    favoriteLanguages: List<String> = listOf("tr", "en"),
): List<SubtitleSearchResult> {
    val favRank: (String) -> Int = { lang ->
        val idx = favoriteLanguages.indexOfFirst { it.equals(lang, ignoreCase = true) }
        if (idx >= 0) idx else favoriteLanguages.size
    }
    return results.sortedWith(
        compareBy<SubtitleSearchResult> { favRank(it.language) }
            .thenByDescending { it.hashMatch }
            .thenByDescending { it.fromTrusted }
            .thenBy { it.machineTranslated }          // false (human) sorts before true (MT)
            .thenByDescending { it.downloadCount }
            .thenByDescending { it.rating },
    )
}
