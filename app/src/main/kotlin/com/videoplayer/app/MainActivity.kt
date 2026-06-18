package com.videoplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.videoplayer.app.player.HardwareKeyGuard
import com.videoplayer.app.theme.AppTheme

class MainActivity : ComponentActivity(), HardwareKeyGuard {
    @Volatile private var hardwareKeysBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                VideoPlayerApp()
            }
        }
    }

    override fun setHardwareKeysBlocked(blocked: Boolean) {
        hardwareKeysBlocked = blocked
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (hardwareKeysBlocked) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
