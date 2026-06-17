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
}
