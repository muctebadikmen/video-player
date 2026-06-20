// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.saf.InMemorySourceStore
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.core.model.MediaRepository

/** Wraps a single global repo as a Global-only LibrarySourceManager for existing VM tests. */
fun fakeSourceManager(repo: MediaRepository): LibrarySourceManager =
    LibrarySourceManager(InMemorySourceStore(), repo) { repo }
