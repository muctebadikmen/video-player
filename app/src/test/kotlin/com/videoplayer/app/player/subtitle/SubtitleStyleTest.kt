// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleStyleTest {

    @Test fun `outline is white text with black outline and no background`() {
        val s = subtitleStyleSpec(SubtitleStyle.OUTLINE)
        assertThat(s.textColor).isEqualTo(0xFFFFFFFF)
        assertThat(s.edge).isEqualTo(SubtitleEdge.OUTLINE)
        assertThat(s.edgeColor).isEqualTo(0xFF000000)
        assertThat(s.backgroundColor).isEqualTo(0x00000000)
    }

    @Test fun `drop shadow is white text with shadow edge and no background`() {
        val s = subtitleStyleSpec(SubtitleStyle.DROP_SHADOW)
        assertThat(s.edge).isEqualTo(SubtitleEdge.DROP_SHADOW)
        assertThat(s.backgroundColor).isEqualTo(0x00000000)
    }

    @Test fun `background box is white text on translucent black with no edge`() {
        val s = subtitleStyleSpec(SubtitleStyle.BACKGROUND_BOX)
        assertThat(s.edge).isEqualTo(SubtitleEdge.NONE)
        assertThat(s.backgroundColor).isEqualTo(0xA6000000) // ~65% black
    }

    @Test fun `system falls back to outline spec for the custom overlay path`() {
        assertThat(subtitleStyleSpec(SubtitleStyle.SYSTEM)).isEqualTo(subtitleStyleSpec(SubtitleStyle.OUTLINE))
    }

    @Test fun `parse name is safe and defaults to outline`() {
        assertThat(subtitleStyleFromName("DROP_SHADOW")).isEqualTo(SubtitleStyle.DROP_SHADOW)
        assertThat(subtitleStyleFromName("bogus")).isEqualTo(SubtitleStyle.OUTLINE)
        assertThat(subtitleStyleFromName(null)).isEqualTo(SubtitleStyle.OUTLINE)
    }
}
