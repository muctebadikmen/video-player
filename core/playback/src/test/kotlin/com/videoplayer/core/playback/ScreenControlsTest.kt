// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenControlsTest {
    @Test fun `nextOrientationMode cycles in declared order and wraps`() {
        assertThat(nextOrientationMode(OrientationMode.AUTO)).isEqualTo(OrientationMode.PORTRAIT)
        assertThat(nextOrientationMode(OrientationMode.PORTRAIT)).isEqualTo(OrientationMode.LANDSCAPE)
        assertThat(nextOrientationMode(OrientationMode.LANDSCAPE)).isEqualTo(OrientationMode.REVERSE_LANDSCAPE)
        assertThat(nextOrientationMode(OrientationMode.REVERSE_LANDSCAPE)).isEqualTo(OrientationMode.AUTO)
    }

    @Test fun `lock-hold constants are three seconds`() {
        assertThat(UNLOCK_HOLD_MS).isEqualTo(3_000L)
        assertThat(LOCK_HINT_VISIBLE_MS).isEqualTo(3_000L)
    }
}