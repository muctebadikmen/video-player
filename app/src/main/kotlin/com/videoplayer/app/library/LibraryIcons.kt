// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 2×2 grid glyph for the grid-view toggle (Icon applies the tint, so the fill color is a placeholder). */
val GridViewIcon: ImageVector = ImageVector.Builder(
    name = "GridView",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
        moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
        moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
    }
}.build()

/** Descending bars glyph for the sort menu. */
val SortIcon: ImageVector = ImageVector.Builder(
    name = "Sort",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
        moveTo(3f, 11f); horizontalLineTo(15f); verticalLineTo(13f); horizontalLineTo(3f); close()
        moveTo(3f, 16f); horizontalLineTo(9f); verticalLineTo(18f); horizontalLineTo(3f); close()
    }
}.build()