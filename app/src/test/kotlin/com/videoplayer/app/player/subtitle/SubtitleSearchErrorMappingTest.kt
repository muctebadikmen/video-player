// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.data.opensubtitles.OsError
import org.junit.Test

class SubtitleSearchErrorMappingTest {

    @Test fun `http error surfaces the server-provided reason instead of a generic code`() {
        val state = mapSearchError(OsError.Http(400, "query >= 3 characters"))
        assertThat(state).isEqualTo(SearchUiState.Error("query >= 3 characters"))
    }

    @Test fun `401 maps to a session-expired hint, not the raw message`() {
        val state = mapSearchError(OsError.Http(401, "invalid token"))
        assertThat(state).isEqualTo(SearchUiState.Error("Session expired — log in again in Settings"))
    }

    @Test fun `http error with a blank message falls back to the status code`() {
        val state = mapSearchError(OsError.Http(500, ""))
        assertThat(state).isEqualTo(SearchUiState.Error("Server error (500)"))
    }

    @Test fun `quota exhausted maps to the quota state`() {
        assertThat(mapSearchError(OsError.QuotaExhausted)).isEqualTo(SearchUiState.QuotaExhausted)
    }

    @Test fun `offline maps to the offline state`() {
        assertThat(mapSearchError(OsError.Offline)).isEqualTo(SearchUiState.Offline)
    }
}
