// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = AccentTeal,
    background = TrueBlack,
    surface = DarkSurface,
    onBackground = OnDark,
    onSurface = OnDark,
    onSurfaceVariant = OnDarkMuted,
)

private val LightColors = lightColorScheme(
    primary = AccentTeal,
)

/**
 * App theme. Honors Material You dynamic color on Android 12+ (the spec's
 * default), falling back to a hand-tuned true-black dark scheme so AMOLED
 * screens stay genuinely black. Video apps default to dark, but we still
 * respect the system light/dark setting.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && supportsDynamic -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}