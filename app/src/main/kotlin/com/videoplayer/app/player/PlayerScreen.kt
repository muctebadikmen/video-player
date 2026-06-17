package com.videoplayer.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.videoplayer.app.engine.Media3PlaybackEngine
import com.videoplayer.core.model.MediaItem

/**
 * Full-screen playback for a single [MediaItem]. Owns a [Media3PlaybackEngine]
 * for its lifetime, binds it to a [PlayerView], starts playback, and tears down
 * cleanly when it leaves composition: the view's player reference is cleared
 * first, then the engine is released (avoids a view holding a released player).
 */
@Composable
fun PlayerScreen(
    item: MediaItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember(item.uri) { Media3PlaybackEngine(context) }

    BackHandler(onBack = onBack)

    LaunchedEffect(engine) {
        engine.setMediaUri(item.uri)
        engine.play()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).also { view ->
                view.setBackgroundColor(android.graphics.Color.BLACK)
                engine.attachToView(view)
            }
        },
        onRelease = { view ->
            view.player = null
            engine.release()
        },
    )
}
