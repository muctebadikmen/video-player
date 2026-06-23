// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.memory.VideoThumbnailEntity
import com.videoplayer.app.thumbnail.ThumbnailController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThumbnailController(
    private val rows: MutableStateFlow<List<VideoThumbnailEntity>> = MutableStateFlow(emptyList()),
) : ThumbnailController {
    val ensured = mutableListOf<Pair<String, Long>>()
    val customSet = mutableListOf<Pair<String, Long>>()
    val reset = mutableListOf<String>()
    fun emit(list: List<VideoThumbnailEntity>) { rows.value = list }
    override fun observeAll(): Flow<List<VideoThumbnailEntity>> = rows
    override fun ensureAutoThumbnail(mediaUri: String, durationMs: Long) { ensured += mediaUri to durationMs }
    override fun setCustomThumbnailFromFrame(mediaUri: String, frameMs: Long) { customSet += mediaUri to frameMs }
    override fun resetToAuto(mediaUri: String) { reset += mediaUri }
}
