// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldSpeedAndSubtitleMathTest {

    @Test fun `one pointer uses one-finger speed`() {
        assertThat(boostSpeedForPointers(1, 2f, 3f)).isEqualTo(2f)
    }

    @Test fun `two or more pointers use two-finger speed`() {
        assertThat(boostSpeedForPointers(2, 2f, 3f)).isEqualTo(3f)
        assertThat(boostSpeedForPointers(3, 2f, 3f)).isEqualTo(3f)
    }

    @Test fun `zero pointers falls back to one-finger speed`() {
        assertThat(boostSpeedForPointers(0, 2f, 3f)).isEqualTo(2f)
    }

    @Test fun `format whole speed drops decimal`() {
        assertThat(formatSpeedLabel(2f)).isEqualTo("2×")
        assertThat(formatSpeedLabel(3f)).isEqualTo("3×")
    }

    @Test fun `format fractional speed strips trailing zeros`() {
        assertThat(formatSpeedLabel(2.5f)).isEqualTo("2.5×")
        assertThat(formatSpeedLabel(2.55f)).isEqualTo("2.55×")
    }

    @Test fun `format quarter presets keep two decimals`() {
        assertThat(formatSpeedLabel(0.25f)).isEqualTo("0.25×")
        assertThat(formatSpeedLabel(0.75f)).isEqualTo("0.75×")
        assertThat(formatSpeedLabel(1.25f)).isEqualTo("1.25×")
    }

    @Test fun `clampHoldSpeed bounds to 1 to 4`() {
        assertThat(clampHoldSpeed(0.2f)).isEqualTo(1f)
        assertThat(clampHoldSpeed(9f)).isEqualTo(4f)
        assertThat(clampHoldSpeed(2.5f)).isEqualTo(2.5f)
    }

    @Test fun `clampSubtitleSize bounds to 0_04 to 0_10`() {
        assertThat(clampSubtitleSize(0.01f)).isEqualTo(0.04f)
        assertThat(clampSubtitleSize(0.5f)).isEqualTo(0.10f)
        assertThat(clampSubtitleSize(0.06f)).isEqualTo(0.06f)
    }

    @Test fun `clampSubtitleBottomPadding bounds to 0_02 to 0_50`() {
        assertThat(clampSubtitleBottomPadding(0f)).isEqualTo(0.02f)
        assertThat(clampSubtitleBottomPadding(0.9f)).isEqualTo(0.50f)
        assertThat(clampSubtitleBottomPadding(0.2f)).isEqualTo(0.2f)
    }

    @Test fun `dragging up increases bottom padding (moves subtitle up)`() {
        // dragYpx negative = upward; height 1000px, drag up 100px => +0.1
        val next = applySubtitleBottomPadding(0.1f, -100f, 1000f)
        assertThat(next).isWithin(1e-4f).of(0.2f)
    }

    @Test fun `dragging down decreases bottom padding and clamps`() {
        val next = applySubtitleBottomPadding(0.1f, 1000f, 1000f)
        assertThat(next).isEqualTo(0.02f) // clamped floor
    }

    @Test fun `applySubtitleBottomPadding ignores zero height`() {
        assertThat(applySubtitleBottomPadding(0.1f, -100f, 0f)).isEqualTo(0.1f)
    }

    @Test fun `pinch zoom scales size and clamps`() {
        assertThat(applySubtitleSize(0.05f, 1.2f)).isWithin(1e-4f).of(0.06f)
        assertThat(applySubtitleSize(0.05f, 10f)).isEqualTo(0.10f) // clamp ceiling
    }
}
