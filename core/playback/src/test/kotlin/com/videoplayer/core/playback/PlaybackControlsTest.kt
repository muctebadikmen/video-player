package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControlsTest {
    @Test fun `abLoopTarget null when loop incomplete`() {
        assertThat(abLoopTarget(50_000, AbLoop(startMs = 10_000, endMs = null))).isNull()
        assertThat(abLoopTarget(50_000, AbLoop())).isNull()
    }
    @Test fun `abLoopTarget null before end point`() {
        assertThat(abLoopTarget(15_000, AbLoop(10_000, 20_000))).isNull()
    }
    @Test fun `abLoopTarget returns start at or after end point`() {
        assertThat(abLoopTarget(20_000, AbLoop(10_000, 20_000))).isEqualTo(10_000)
        assertThat(abLoopTarget(25_000, AbLoop(10_000, 20_000))).isEqualTo(10_000)
    }
    @Test fun `abLoopTarget null when end not after start`() {
        assertThat(abLoopTarget(30_000, AbLoop(20_000, 20_000))).isNull()
        assertThat(abLoopTarget(30_000, AbLoop(20_000, 10_000))).isNull()
    }
    @Test fun `isComplete reflects both points and ordering`() {
        assertThat(AbLoop(1, 2).isComplete).isTrue()
        assertThat(AbLoop(2, 1).isComplete).isFalse()
        assertThat(AbLoop(1, null).isComplete).isFalse()
    }
    @Test fun `clampSpeed bounds to 0_25 and 4`() {
        assertThat(clampSpeed(0.1f)).isEqualTo(0.25f)
        assertThat(clampSpeed(5f)).isEqualTo(4f)
        assertThat(clampSpeed(1.5f)).isEqualTo(1.5f)
    }
    @Test fun `speed presets are within bounds and include 1x`() {
        assertThat(SPEED_PRESETS).contains(1f)
        assertThat(SPEED_PRESETS.all { it in MIN_SPEED..MAX_SPEED }).isTrue()
    }
    @Test fun `sleepRemainingMs is non-negative`() {
        assertThat(sleepRemainingMs(10_000, 3_000)).isEqualTo(7_000)
        assertThat(sleepRemainingMs(10_000, 12_000)).isEqualTo(0)
    }
    @Test fun `isSleepExpired when now reaches deadline`() {
        assertThat(isSleepExpired(10_000, 9_999)).isFalse()
        assertThat(isSleepExpired(10_000, 10_000)).isTrue()
    }
}
