// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleSearchTest {

    private fun r(
        fileId: Long,
        language: String,
        downloadCount: Int = 0,
        rating: Double = 0.0,
        fromTrusted: Boolean = false,
        machineTranslated: Boolean = false,
        hashMatch: Boolean = false,
    ) = SubtitleSearchResult(
        fileId = fileId, fileName = "f$fileId.srt", language = language, release = "",
        downloadCount = downloadCount, rating = rating, fromTrusted = fromTrusted,
        machineTranslated = machineTranslated, hashMatch = hashMatch,
    )

    @Test fun `favorite languages are pinned to the top in order`() {
        val ranked = rankSubtitleResults(
            listOf(r(1, "de", downloadCount = 9999), r(2, "en", downloadCount = 1), r(3, "tr", downloadCount = 1)),
            favoriteLanguages = listOf("tr", "en"),
        )
        // tr first, then en, then the non-favorite de last — regardless of download count.
        assertThat(ranked.map { it.fileId }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test fun `hash match beats trusted, trusted beats downloads, downloads beat rating`() {
        val ranked = rankSubtitleResults(
            listOf(
                r(1, "en", downloadCount = 5000, rating = 9.0),                 // plain popular
                r(2, "en", downloadCount = 10, fromTrusted = true),             // trusted
                r(3, "en", downloadCount = 1, hashMatch = true),                // exact file
                r(4, "en", downloadCount = 8000, rating = 1.0),                 // most popular, not trusted
            ),
            favoriteLanguages = listOf("en"),
        )
        // hashMatch (3) → trusted (2) → most downloads among the rest (4) → then (1)
        assertThat(ranked.map { it.fileId }).containsExactly(3L, 2L, 4L, 1L).inOrder()
    }

    @Test fun `machine translated is demoted below a human result of equal standing`() {
        val ranked = rankSubtitleResults(
            listOf(
                r(1, "tr", downloadCount = 100, machineTranslated = true),
                r(2, "tr", downloadCount = 100, machineTranslated = false),
            ),
            favoriteLanguages = listOf("tr"),
        )
        assertThat(ranked.map { it.fileId }).containsExactly(2L, 1L).inOrder()
    }

    @Test fun `download count and rating break ties numerically`() {
        val ranked = rankSubtitleResults(
            listOf(
                r(1, "en", downloadCount = 100, rating = 5.0),
                r(2, "en", downloadCount = 100, rating = 8.5),  // same downloads, higher rating
                r(3, "en", downloadCount = 250, rating = 1.0),  // most downloads
            ),
            favoriteLanguages = listOf("en"),
        )
        assertThat(ranked.map { it.fileId }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test fun `empty input yields empty output`() {
        assertThat(rankSubtitleResults(emptyList())).isEmpty()
    }
}
