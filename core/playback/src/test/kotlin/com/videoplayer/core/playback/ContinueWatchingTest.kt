// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContinueWatchingTest {
    @Test fun `progressFraction is position over duration`() {
        assertThat(progressFraction(30_000, 120_000)).isWithin(0.0001f).of(0.25f)
    }
    @Test fun `progressFraction clamps to range and handles unknown duration`() {
        assertThat(progressFraction(50, 0)).isEqualTo(0f)
        assertThat(progressFraction(200, 100)).isEqualTo(1f)
        assertThat(progressFraction(-5, 100)).isEqualTo(0f)
    }
    @Test fun `continueWatching keeps only resumable entries`() {
        val entries = listOf(
            WatchProgress("a", positionMs = 30_000, durationMs = 120_000, updatedAtEpochMs = 1), // resumable
            WatchProgress("b", positionMs = 1_000, durationMs = 120_000, updatedAtEpochMs = 2),   // too early -> drop
            WatchProgress("c", positionMs = 119_000, durationMs = 120_000, updatedAtEpochMs = 3), // near end -> drop
        )
        assertThat(continueWatching(entries).map { it.mediaUri }).containsExactly("a")
    }
    @Test fun `continueWatching orders by most recently updated`() {
        val entries = listOf(
            WatchProgress("a", 30_000, 120_000, updatedAtEpochMs = 10),
            WatchProgress("b", 40_000, 120_000, updatedAtEpochMs = 30),
            WatchProgress("c", 50_000, 120_000, updatedAtEpochMs = 20),
        )
        assertThat(continueWatching(entries).map { it.mediaUri }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `continueWatching respects the limit`() {
        val entries = (1..30).map { WatchProgress("u$it", 30_000, 120_000, updatedAtEpochMs = it.toLong()) }
        assertThat(continueWatching(entries, limit = 5)).hasSize(5)
    }
}