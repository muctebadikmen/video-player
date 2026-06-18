package com.videoplayer.app.player.gestures

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AspectModeTest {

    @Test fun `cycles fit fill zoom 16-9 4-3 and back`() {
        assertThat(nextAspectMode(AspectMode.FIT)).isEqualTo(AspectMode.FILL)
        assertThat(nextAspectMode(AspectMode.FILL)).isEqualTo(AspectMode.ZOOM)
        assertThat(nextAspectMode(AspectMode.ZOOM)).isEqualTo(AspectMode.RATIO_16_9)
        assertThat(nextAspectMode(AspectMode.RATIO_16_9)).isEqualTo(AspectMode.RATIO_4_3)
        assertThat(nextAspectMode(AspectMode.RATIO_4_3)).isEqualTo(AspectMode.FIT)
    }
}
