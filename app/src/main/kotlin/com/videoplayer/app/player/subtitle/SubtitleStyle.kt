// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

/** User-selectable subtitle look. SYSTEM defers to OS caption settings (embedded path only). */
enum class SubtitleStyle { OUTLINE, DROP_SHADOW, BACKGROUND_BOX, SYSTEM }

/** Edge treatment, mapped per render path (Compose stroke/shadow or CaptionStyleCompat edge). */
enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }

/** Framework-agnostic style description (ARGB as Long) shared by both subtitle render paths. */
data class SubtitleStyleSpec(
    val textColor: Long,
    val edge: SubtitleEdge,
    val edgeColor: Long,
    val backgroundColor: Long,
)

private const val WHITE = 0xFFFFFFFFL
private const val BLACK = 0xFF000000L
private const val TRANSPARENT = 0x00000000L
private const val BLACK_65 = 0xA6000000L // ~65% opaque black box

/** Concrete spec for a style. SYSTEM reuses OUTLINE for the custom (external) overlay. */
fun subtitleStyleSpec(style: SubtitleStyle): SubtitleStyleSpec = when (style) {
    SubtitleStyle.OUTLINE, SubtitleStyle.SYSTEM ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.OUTLINE, BLACK, TRANSPARENT)
    SubtitleStyle.DROP_SHADOW ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.DROP_SHADOW, BLACK, TRANSPARENT)
    SubtitleStyle.BACKGROUND_BOX ->
        SubtitleStyleSpec(WHITE, SubtitleEdge.NONE, TRANSPARENT, BLACK_65)
}

/** Safe parse of a persisted style name; anything unknown → OUTLINE. */
fun subtitleStyleFromName(name: String?): SubtitleStyle =
    SubtitleStyle.entries.firstOrNull { it.name == name } ?: SubtitleStyle.OUTLINE
