// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoplayer.app.player.gestures.DEFAULT_SUBTITLE_SIZE_FRACTION
import com.videoplayer.app.player.gestures.HOLD_SPEED_MAX
import com.videoplayer.app.player.gestures.HOLD_SPEED_MIN
import com.videoplayer.app.player.gestures.SUBTITLE_POS_MAX
import com.videoplayer.app.player.gestures.SUBTITLE_POS_MIN
import com.videoplayer.app.player.gestures.SUBTITLE_SIZE_MAX
import com.videoplayer.app.player.gestures.SUBTITLE_SIZE_MIN
import com.videoplayer.app.player.gestures.formatSpeedLabel
import com.videoplayer.app.player.subtitle.CueOverlay
import com.videoplayer.app.player.subtitle.SubtitleStyle

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalContext.current))
    val backgroundEnabled by vm.backgroundPlaybackEnabled.collectAsStateWithLifecycle()
    val osCreds by vm.osCredentials.collectAsStateWithLifecycle()
    val osStatus by vm.osLoginStatus.collectAsStateWithLifecycle()
    val holdOne by vm.holdSpeedOneFinger.collectAsStateWithLifecycle()
    val holdTwo by vm.holdSpeedTwoFinger.collectAsStateWithLifecycle()
    val subStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val subSize by vm.subtitleSizeFraction.collectAsStateWithLifecycle()
    val subPos by vm.subtitleBottomPaddingFraction.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            SettingSwitchRow(
                title = "Background playback",
                subtitle = "Keep playing audio and Picture-in-Picture when you leave the player",
                checked = backgroundEnabled,
                onCheckedChange = vm::setBackgroundPlayback,
            )
            HorizontalDivider()
            SectionHeader("Playback gestures")
            SettingSliderRow(
                title = "Hold to speed up · 1 finger",
                valueLabel = "${formatSpeedLabel(holdOne)}",
                value = holdOne, valueRange = HOLD_SPEED_MIN..HOLD_SPEED_MAX, steps = 29,
                onValueChange = vm::setHoldSpeedOneFinger,
            )
            SettingSliderRow(
                title = "Hold to speed up · 2 fingers",
                valueLabel = "${formatSpeedLabel(holdTwo)}",
                value = holdTwo, valueRange = HOLD_SPEED_MIN..HOLD_SPEED_MAX, steps = 29,
                onValueChange = vm::setHoldSpeedTwoFinger,
            )
            HorizontalDivider()
            SectionHeader("Subtitles")
            SubtitleStylePicker(selected = subStyle, onSelect = vm::setSubtitleStyle)
            SettingSliderRow(
                title = "Subtitle size",
                valueLabel = "${(subSize / DEFAULT_SUBTITLE_SIZE_FRACTION * 100).toInt()}%",
                value = subSize, valueRange = SUBTITLE_SIZE_MIN..SUBTITLE_SIZE_MAX, steps = 0,
                onValueChange = vm::setSubtitleSize,
            )
            SettingSliderRow(
                title = "Subtitle position (height from bottom)",
                valueLabel = "${(subPos * 100).toInt()}%",
                value = subPos, valueRange = SUBTITLE_POS_MIN..SUBTITLE_POS_MAX, steps = 0,
                onValueChange = vm::setSubtitlePosition,
            )
            SubtitlePreview(style = subStyle, sizeFraction = subSize)
            HorizontalDivider()
            OpenSubtitlesSettings(
                creds = osCreds,
                loginStatus = osStatus,
                onApiKeyChange = vm::setApiKey,
                onUsernameChange = vm::setUsername,
                onFavLangsChange = vm::setFavoriteLanguages,
                onLogin = vm::login,
                onLogout = vm::logout,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun SubtitleStylePicker(selected: SubtitleStyle, onSelect: (SubtitleStyle) -> Unit) {
    val options = listOf(
        SubtitleStyle.OUTLINE to "Outline",
        SubtitleStyle.DROP_SHADOW to "Drop shadow",
        SubtitleStyle.BACKGROUND_BOX to "Background box",
        SubtitleStyle.SYSTEM to "Follow system",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEach { (style, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(style) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected == style, onClick = { onSelect(style) })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SubtitlePreview(style: SubtitleStyle, sizeFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(96.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        CueOverlay(
            text = "Sample subtitle",
            style = style,
            sizeFraction = (sizeFraction * 1.6f),
            bottomPaddingFraction = 0.1f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
