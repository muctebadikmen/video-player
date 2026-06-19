// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/** Index of [startUri] within [uris], or 0 when absent or the list is empty. */
fun startIndexFor(uris: List<String>, startUri: String): Int =
    uris.indexOf(startUri).coerceAtLeast(0)