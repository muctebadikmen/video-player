package com.videoplayer.app.player

/** Lets the player ask the hosting Activity to swallow volume/mute hardware keys (Kids Lock). */
interface HardwareKeyGuard {
    fun setHardwareKeysBlocked(blocked: Boolean)
}
