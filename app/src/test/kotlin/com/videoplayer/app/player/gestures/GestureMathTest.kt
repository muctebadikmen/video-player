// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.gestures

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureMathTest {

    @Test fun `left half is brightness`() {
        assertThat(verticalSide(10f, 100f)).isEqualTo(VerticalSide.BRIGHTNESS)
    }

    @Test fun `right half is volume`() {
        assertThat(verticalSide(80f, 100f)).isEqualTo(VerticalSide.VOLUME)
    }

    @Test fun `zero width defaults to brightness`() {
        assertThat(verticalSide(0f, 0f)).isEqualTo(VerticalSide.BRIGHTNESS)
    }

    @Test fun `drag up raises brightness`() {
        assertThat(applyBrightness(0.5f, -50f, 100f)).isWithin(1e-4f).of(1.0f)
    }

    @Test fun `brightness clamps at 1`() {
        assertThat(applyBrightness(0.9f, -100f, 100f)).isEqualTo(1f)
    }

    @Test fun `brightness clamps at 0`() {
        assertThat(applyBrightness(0.1f, 100f, 100f)).isEqualTo(0f)
    }

    @Test fun `volume can exceed 1 up to 2`() {
        assertThat(applyVolumeFactor(1f, -100f, 100f)).isEqualTo(2f)
    }

    @Test fun `volume clamps at 0`() {
        assertThat(applyVolumeFactor(0.1f, 100f, 100f)).isEqualTo(0f)
    }

    @Test fun `seek delta positive for rightward drag`() {
        assertThat(horizontalSeekDeltaMs(50f, 100f)).isEqualTo(45_000L)
    }

    @Test fun `seek delta negative for leftward drag`() {
        assertThat(horizontalSeekDeltaMs(-50f, 100f)).isEqualTo(-45_000L)
    }

    @Test fun `seek zero width is zero`() {
        assertThat(horizontalSeekDeltaMs(50f, 0f)).isEqualTo(0L)
    }
}