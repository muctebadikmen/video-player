// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

/** Which body the library should render, after permission + loading + content are known. */
enum class LibraryBodyState { PERMISSION, LOADING, EMPTY, CONTENT }

/**
 * Resolve the library body to show. Permission takes priority (nothing loads without it),
 * then loading (scan in progress), then empty (scan complete, no items), else content.
 */
fun libraryBodyState(
    hasPermission: Boolean,
    isLoading: Boolean,
    hasAnyItems: Boolean,
): LibraryBodyState = when {
    !hasPermission -> LibraryBodyState.PERMISSION
    isLoading -> LibraryBodyState.LOADING
    !hasAnyItems -> LibraryBodyState.EMPTY
    else -> LibraryBodyState.CONTENT
}