// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DurationTest {

    @Test
    fun `formats seconds under a minute`() {
        assertThat(formatDuration(9_000)).isEqualTo("0:09")
    }

    @Test
    fun `formats minutes and seconds`() {
        assertThat(formatDuration(65_000)).isEqualTo("1:05")
    }

    @Test
    fun `formats hours with zero-padded minutes and seconds`() {
        assertThat(formatDuration(3_909_000)).isEqualTo("1:05:09")
    }

    @Test
    fun `clamps negative durations to zero`() {
        assertThat(formatDuration(-5)).isEqualTo("0:00")
    }
}