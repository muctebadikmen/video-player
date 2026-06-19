// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreferencePrecedenceTest {
    @Test fun `per-file value wins over folder and global`() {
        assertThat(resolvePreference(2f, 3f, 1f)).isEqualTo(2f)
    }
    @Test fun `folder value used when file is null`() {
        assertThat(resolvePreference(null, 3f, 1f)).isEqualTo(3f)
    }
    @Test fun `global value used when file and folder are null`() {
        assertThat(resolvePreference<Float>(null, null, 1f)).isEqualTo(1f)
    }
}