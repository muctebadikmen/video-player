package com.videoplayer.app.engine

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.videoplayer.core.playback.PlayerStatus
import org.junit.Test

class Media3StateMapperTest {

    @Test
    fun `exoStateToStatus maps STATE_IDLE to IDLE`() {
        assertThat(exoStateToStatus(Player.STATE_IDLE)).isEqualTo(PlayerStatus.IDLE)
    }

    @Test
    fun `exoStateToStatus maps STATE_BUFFERING to BUFFERING`() {
        assertThat(exoStateToStatus(Player.STATE_BUFFERING)).isEqualTo(PlayerStatus.BUFFERING)
    }

    @Test
    fun `exoStateToStatus maps STATE_READY to READY`() {
        assertThat(exoStateToStatus(Player.STATE_READY)).isEqualTo(PlayerStatus.READY)
    }

    @Test
    fun `exoStateToStatus maps STATE_ENDED to ENDED`() {
        assertThat(exoStateToStatus(Player.STATE_ENDED)).isEqualTo(PlayerStatus.ENDED)
    }

    @Test
    fun `videoAspectRatio computes pixel-corrected ratio`() {
        assertThat(videoAspectRatio(1920, 1080, 1f)).isWithin(1e-4f).of(16f / 9f)
        assertThat(videoAspectRatio(640, 480, 1f)).isWithin(1e-4f).of(4f / 3f)
        // Anamorphic: 720x480 with 1.5 pixel ratio -> 16:9 display.
        assertThat(videoAspectRatio(720, 480, 1.5f)).isWithin(1e-4f).of(2.25f)
    }

    @Test
    fun `videoAspectRatio is zero when a dimension is unknown`() {
        assertThat(videoAspectRatio(0, 1080, 1f)).isEqualTo(0f)
        assertThat(videoAspectRatio(1920, 0, 1f)).isEqualTo(0f)
    }
}
