// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.player.subtitle.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryPrefsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun repo(): SettingsRepository {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb") }
        return SettingsRepository(ds)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `defaults match spec`() = runTest {
        val r = repo()
        assertThat(r.holdSpeedOneFinger.first()).isEqualTo(2.0f)
        assertThat(r.holdSpeedTwoFinger.first()).isEqualTo(3.0f)
        assertThat(r.subtitleStyle.first()).isEqualTo(SubtitleStyle.OUTLINE)
        assertThat(r.subtitleSizeFraction.first()).isEqualTo(0.0533f)
        assertThat(r.subtitleBottomPaddingFraction.first()).isEqualTo(0.08f)
    }

    @Test fun `setters round-trip and clamp`() = runTest {
        val r = repo()
        r.setHoldSpeedOneFinger(99f)   // clamps to 4
        r.setHoldSpeedTwoFinger(2.5f)
        r.setSubtitleStyle(SubtitleStyle.BACKGROUND_BOX)
        r.setSubtitleSizeFraction(0.5f)            // clamps to 0.10
        r.setSubtitleBottomPaddingFraction(0f)     // clamps to 0.02
        assertThat(r.holdSpeedOneFinger.first()).isEqualTo(4f)
        assertThat(r.holdSpeedTwoFinger.first()).isEqualTo(2.5f)
        assertThat(r.subtitleStyle.first()).isEqualTo(SubtitleStyle.BACKGROUND_BOX)
        assertThat(r.subtitleSizeFraction.first()).isEqualTo(0.10f)
        assertThat(r.subtitleBottomPaddingFraction.first()).isEqualTo(0.02f)
    }
}
