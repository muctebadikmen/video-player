package com.videoplayer.app.data.memory

/** The settings the player should apply when a file opens, after precedence + resume policy. */
data class ResolvedStartSettings(
    val startPositionMs: Long,
    val speed: Float,
    val aspectMode: String,
    /** Per-file orientation override (ActivityInfo.screenOrientation int), or null for no override. */
    val orientation: Int?,
    /** Per-file subtitle memory token ("embedded:<id>" / "ext:<uri>" / null = none). */
    val subtitleTrackId: String?,
    /** Per-file custom-subtitle sync offset in ms (0 when none). */
    val subtitleOffsetMs: Long,
)
