package com.videoplayer.app.player.gestures

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AspectModeTest {

    @Test fun `cycles fit to fill to zoom and back`() {
        assertThat(nextAspectMode(AspectMode.FIT)).isEqualTo(AspectMode.FILL)
        assertThat(nextAspectMode(AspectMode.FILL)).isEqualTo(AspectMode.ZOOM)
        assertThat(nextAspectMode(AspectMode.ZOOM)).isEqualTo(AspectMode.FIT)
    }
}
