// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

/**
 * A selectable external or sibling subtitle file.
 * [uri] is the content/file URI string (the stable key); [label] is the menu text.
 */
data class SubtitleOption(val uri: String, val label: String)