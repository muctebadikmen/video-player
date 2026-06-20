// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import kotlinx.coroutines.flow.Flow

/** Minimal seam used by [com.videoplayer.app.library.LibraryViewModel]. */
interface GridSizePreferences {
    val gridColumns: Flow<Int>
    suspend fun setGridColumns(columns: Int)
}
