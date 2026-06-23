// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailScoringTest {

    @Test fun `pickBestFrame throws on empty`() {
        try {
            pickBestFrame(emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }

    @Test fun `pure black candidates fall back to first index`() {
        val black = FrameStats(avgLuminance = 0.0, variance = 0.0)
        assertThat(pickBestFrame(listOf(black, black, black))).isEqualTo(0)
    }

    @Test fun `textured mid-luminance frame beats black and white`() {
        val black = FrameStats(0.01, 0.0)
        val textured = FrameStats(0.45, 0.06)
        val white = FrameStats(0.99, 0.0)
        assertThat(pickBestFrame(listOf(black, textured, white))).isEqualTo(1)
    }

    @Test fun `among non-extreme frames, more detail wins`() {
        val flat = FrameStats(0.5, 0.001)
        val detailed = FrameStats(0.5, 0.08)
        assertThat(pickBestFrame(listOf(flat, detailed))).isEqualTo(1)
    }

    @Test fun `near-black is penalized below a detailed mid frame`() {
        val nearBlack = FrameStats(0.05, 0.02)
        val mid = FrameStats(0.4, 0.03)
        assertThat(pickBestFrame(listOf(nearBlack, mid))).isEqualTo(1)
    }

    @Test fun `luminanceStats computes mean and variance of a black-and-white split`() {
        // 2 black + 2 white pixels: mean luma = 0.5, variance = 0.25
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val stats = luminanceStats(intArrayOf(black, black, white, white))
        assertThat(stats.avgLuminance).isWithin(1e-6).of(0.5)
        assertThat(stats.variance).isWithin(1e-6).of(0.25)
    }

    @Test fun `luminanceStats of all-black is zero`() {
        val stats = luminanceStats(IntArray(16) { 0xFF000000.toInt() })
        assertThat(stats.avgLuminance).isWithin(1e-6).of(0.0)
        assertThat(stats.variance).isWithin(1e-6).of(0.0)
    }

    @Test fun `empty pixels yields zero stats`() {
        assertThat(luminanceStats(IntArray(0))).isEqualTo(FrameStats(0.0, 0.0))
    }
}
