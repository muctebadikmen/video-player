// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import com.videoplayer.core.playback.OSDB_CHUNK_BYTES
import com.videoplayer.core.playback.osdbHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Computes the OSDb movie-hash from a content:// URI by reading the first/last 64 KiB + length. */
object MovieHasher {
    private const val TAG = "MovieHasher"

    suspend fun hash(context: Context, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < OSDB_CHUNK_BYTES.toLong() * 2) return@withContext null // also covers statSize < 0
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val head = ByteArray(OSDB_CHUNK_BYTES)
                    val tail = ByteArray(OSDB_CHUNK_BYTES)
                    if (!readFully(ch, head, position = 0L)) return@withContext null
                    if (!readFully(ch, tail, position = size - OSDB_CHUNK_BYTES)) return@withContext null
                    osdbHash(size, head, tail)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movie-hash failed for $uri; falling back to filename search", e)
            null
        }
    }

    private fun readFully(ch: java.nio.channels.FileChannel, out: ByteArray, position: Long): Boolean {
        val buf = ByteBuffer.wrap(out)
        ch.position(position) // seekable read; never skip()
        var read = 0
        while (read < out.size) {
            val n = ch.read(buf)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
