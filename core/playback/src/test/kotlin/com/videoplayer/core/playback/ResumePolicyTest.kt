package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumePolicyTest {
    @Test fun `resumes a mid-file position`() {
        assertThat(effectiveResumePosition(30_000, 120_000)).isEqualTo(30_000)
    }
    @Test fun `too-early position resets to zero`() {
        assertThat(effectiveResumePosition(4_999, 120_000)).isEqualTo(0)
    }
    @Test fun `boundary at min resume is honored`() {
        assertThat(effectiveResumePosition(5_000, 120_000)).isEqualTo(5_000)
    }
    @Test fun `near-end position resets to zero (finished)`() {
        assertThat(effectiveResumePosition(118_000, 120_000)).isEqualTo(0)
    }
    @Test fun `unknown duration honors a valid saved position`() {
        assertThat(effectiveResumePosition(30_000, 0)).isEqualTo(30_000)
    }
    @Test fun `negative saved position resets to zero`() {
        assertThat(effectiveResumePosition(-1, 120_000)).isEqualTo(0)
    }
}
