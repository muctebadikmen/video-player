// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.memory.GridSizePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeGridSizePreferences(initial: Int = 3) : GridSizePreferences {
    private val _columns = MutableStateFlow(initial)
    override val gridColumns: Flow<Int> = _columns
    override suspend fun setGridColumns(columns: Int) { _columns.value = columns }
}
