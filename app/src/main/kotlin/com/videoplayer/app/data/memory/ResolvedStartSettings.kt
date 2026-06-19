// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

/** The settings the player should apply when a file opens, after precedence + resume policy. */
data class ResolvedStartSettings(
    /**
     * The media uri these settings were resolved for. A file open can transiently load a
     * different uri before the playlist index settles, so consumers must ignore a result
     * whose [mediaUri] != the current item's uri (a stale-resolved guard).
     */
    val mediaUri: String,
    val startPositionMs: Long,
    val speed: Float,
    val aspectMode: String,
    /** Per-file orientation override (ActivityInfo.screenOrientation int), or null for no override. */
    val orientation: Int?,
    /** Per-file subtitle memory token ("embedded:<id>" / "ext:<uri>" / null = none). */
    val subtitleTrackId: String?,
    /** Per-file custom-subtitle sync offset in ms (0 when none). */
    val subtitleOffsetMs: Long,
    /** Per-file external-subtitle rate correction (1.0 = unscaled). */
    val subtitleRate: Float,
)