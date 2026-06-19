// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.memory.MemorySource
import com.videoplayer.app.data.memory.PlaybackMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeMemorySource(private val rows: List<PlaybackMemoryEntity>) : MemorySource {
    override fun observeAll(): Flow<List<PlaybackMemoryEntity>> = flowOf(rows)
}