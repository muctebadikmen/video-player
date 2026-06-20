package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultTextTrackTest {
    private fun t(id: String) = TextTrackInfo(id = id, label = id, language = null)

    @Test fun noTracksReturnsNull() {
        assertThat(pickDefaultTextTrack(emptyList(), null, false)).isNull()
    }

    @Test fun freshMediaPicksFirst() {
        assertThat(pickDefaultTextTrack(listOf(t("text:0:0"), t("text:0:1")), null, false))
            .isEqualTo("text:0:0")
    }

    @Test fun alreadySelectedReturnsNull() {
        assertThat(pickDefaultTextTrack(listOf(t("text:0:0")), "text:0:0", false)).isNull()
    }

    @Test fun userDisabledNotReEnabled() {
        assertThat(pickDefaultTextTrack(listOf(t("text:0:0")), null, true)).isNull()
    }
}
