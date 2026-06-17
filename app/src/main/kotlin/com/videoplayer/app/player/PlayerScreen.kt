package com.videoplayer.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.videoplayer.app.engine.Media3PlaybackEngine
import com.videoplayer.core.model.MediaItem

/**
 * Full-screen playback for a single [MediaItem]. Owns a [Media3PlaybackEngine]
 * for its lifetime, binds it to a [PlayerView], starts playback, and releases
 * the engine when it leaves composition.
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

    DisposableEffect(engine) {
        engine.setMediaUri(item.uri)
        engine.play()
        onDispose { engine.release() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).also { view ->
                view.setBackgroundColor(android.graphics.Color.BLACK)
                engine.attachToView(view)
            }
        },
    )
}
