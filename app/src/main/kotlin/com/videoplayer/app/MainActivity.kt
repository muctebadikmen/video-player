package com.videoplayer.app

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.videoplayer.app.player.HardwareKeyGuard
import com.videoplayer.app.player.PipController
import com.videoplayer.app.theme.AppTheme

class MainActivity : ComponentActivity(), HardwareKeyGuard, PipController {
    @Volatile private var hardwareKeysBlocked = false
    private val _pipMode = mutableStateOf(false)
    override val pipMode: State<Boolean> get() = _pipMode

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

    override fun enterPip(aspectNum: Int, aspectDen: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspectNum, aspectDen))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun setAutoEnterPip(enabled: Boolean, aspectNum: Int, aspectDen: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspectNum, aspectDen))
            .setAutoEnterEnabled(enabled)
            .build()
        runCatching { setPictureInPictureParams(params) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pipMode.value = isInPictureInPictureMode
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
