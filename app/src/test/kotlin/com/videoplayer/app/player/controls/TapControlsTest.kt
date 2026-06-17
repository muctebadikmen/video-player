package com.videoplayer.app.player.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapControlsTest {
    @Test fun `left third resolves LEFT`() { assertThat(resolveTapZone(10f, 300f)).isEqualTo(TapZone.LEFT) }
    @Test fun `middle resolves CENTER`() { assertThat(resolveTapZone(150f, 300f)).isEqualTo(TapZone.CENTER) }
    @Test fun `right third resolves RIGHT`() { assertThat(resolveTapZone(290f, 300f)).isEqualTo(TapZone.RIGHT) }
    @Test fun `zero width is CENTER`() { assertThat(resolveTapZone(0f, 0f)).isEqualTo(TapZone.CENTER) }
    @Test fun `double tap maps zones to actions`() {
        assertThat(doubleTapAction(TapZone.LEFT)).isEqualTo(DoubleTapAction.SEEK_BACKWARD)
        assertThat(doubleTapAction(TapZone.CENTER)).isEqualTo(DoubleTapAction.PLAY_PAUSE)
        assertThat(doubleTapAction(TapZone.RIGHT)).isEqualTo(DoubleTapAction.SEEK_FORWARD)
    }
    @Test fun `seek clamps at zero`() { assertThat(seekTarget(3_000, -10_000, 60_000)).isEqualTo(0) }
    @Test fun `seek clamps at duration`() { assertThat(seekTarget(58_000, 10_000, 60_000)).isEqualTo(60_000) }
    @Test fun `seek adds delta in range`() { assertThat(seekTarget(20_000, 10_000, 60_000)).isEqualTo(30_000) }
}
