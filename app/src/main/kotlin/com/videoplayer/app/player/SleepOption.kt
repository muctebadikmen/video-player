// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

/**
 * User-selectable sleep-timer options. [minutes] is non-null for duration-based options;
 * [OFF] and [END_OF_VIDEO] are distinguished by identity, not by [minutes].
 */
enum class SleepOption(val minutes: Int?) {
    OFF(null),
    M15(15),
    M30(30),
    M45(45),
    M60(60),
    END_OF_VIDEO(null);

    val label: String
        get() = when (this) {
            OFF -> "Off"
            M15 -> "15 min"
            M30 -> "30 min"
            M45 -> "45 min"
            M60 -> "60 min"
            END_OF_VIDEO -> "End of video"
        }
}