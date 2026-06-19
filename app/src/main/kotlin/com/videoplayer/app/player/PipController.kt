// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import androidx.compose.runtime.State

/**
 * Lets the player request Picture-in-Picture and observe PiP mode. Implemented by
 * the host Activity (mirrors [HardwareKeyGuard]). No-op below API 26.
 */
interface PipController {
    /** True while the Activity is in PiP mode; recomposes observers when it changes. */
    val pipMode: State<Boolean>

    /** Enter PiP now with the given aspect ratio (API 26+). */
    fun enterPip(aspectNum: Int, aspectDen: Int)

    /** Enable/disable seamless auto-enter-on-home with the given aspect (API 31+). */
    fun setAutoEnterPip(enabled: Boolean, aspectNum: Int, aspectDen: Int)
}