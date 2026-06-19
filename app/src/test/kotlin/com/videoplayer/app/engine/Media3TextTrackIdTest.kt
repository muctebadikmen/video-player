package com.videoplayer.app.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3TextTrackIdTest {
    @Test fun `formats and parses round-trip`() {
        assertThat(textTrackId(0, 1)).isEqualTo("text:0:1")
        assertThat(parseTextTrackId("text:0:1")).isEqualTo(0 to 1)
        assertThat(parseTextTrackId("text:2:3")).isEqualTo(2 to 3)
    }

    @Test fun `parse rejects malformed`() {
        assertThat(parseTextTrackId("")).isNull()
        assertThat(parseTextTrackId("audio:0:1")).isNull()
        assertThat(parseTextTrackId("text:x:1")).isNull()
        assertThat(parseTextTrackId("text:0")).isNull()
    }
}
