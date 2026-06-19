// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/** OSDb hash chunk size: first and last 64 KiB of the file. */
const val OSDB_CHUNK_BYTES = 65536

/**
 * OSDb movie-hash arithmetic. hash = (fileSize + Σ LE-uint64 words of [head] + Σ LE-uint64 words of
 * [tail]), summed with mod-2^64 wraparound (Kotlin Long arithmetic wraps natively), rendered as 16
 * lowercase hex characters. [head] is the first 64 KiB, [tail] the last 64 KiB. Returns null when the
 * file is smaller than two chunks (128 KiB) or a chunk is not exactly [OSDB_CHUNK_BYTES] long.
 */
fun osdbHash(fileSize: Long, head: ByteArray, tail: ByteArray): String? {
    if (fileSize < OSDB_CHUNK_BYTES.toLong() * 2) return null
    if (head.size != OSDB_CHUNK_BYTES || tail.size != OSDB_CHUNK_BYTES) return null

    var hash = fileSize
    hash += sumLittleEndianWords(head)
    hash += sumLittleEndianWords(tail)

    // 16 lowercase hex, zero-padded; toULong handles the high bit without a sign prefix.
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun sumLittleEndianWords(chunk: ByteArray): Long {
    var sum = 0L
    var i = 0
    while (i + 8 <= chunk.size) {
        var word = 0L
        for (b in 0 until 8) {
            word = word or ((chunk[i + b].toLong() and 0xFF) shl (8 * b))
        }
        sum += word // wraps mod 2^64 in Long arithmetic
        i += 8
    }
    return sum
}
