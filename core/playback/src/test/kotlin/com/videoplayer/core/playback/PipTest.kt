package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PipTest {
    @Test fun `pip unavailable below api 26`() {
        assertThat(pipAvailable(25, settingEnabled = true)).isFalse()
    }
    @Test fun `pip available at api 26 when enabled`() {
        assertThat(pipAvailable(26, settingEnabled = true)).isTrue()
    }
    @Test fun `pip unavailable when setting disabled`() {
        assertThat(pipAvailable(31, settingEnabled = false)).isFalse()
    }
    @Test fun `clamp returns 16 to 9 for non-positive ratio`() {
        assertThat(clampPipAspect(0f)).isEqualTo(16 to 9)
        assertThat(clampPipAspect(-1f)).isEqualTo(16 to 9)
    }
    @Test fun `clamp passes a normal ratio through scaled`() {
        assertThat(clampPipAspect(1.7778f)).isEqualTo(17778 to 10000)
    }
    @Test fun `clamp limits a too-wide ratio`() {
        assertThat(clampPipAspect(3f)).isEqualTo(23800 to 10000)
    }
    @Test fun `clamp limits a too-tall ratio`() {
        assertThat(clampPipAspect(0.1f)).isEqualTo(4200 to 10000)
    }
}
