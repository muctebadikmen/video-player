// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OsdbHashTest {

    @Test fun `null when file smaller than two chunks`() {
        assertThat(osdbHash(OSDB_CHUNK_BYTES.toLong() * 2 - 1, ByteArray(0), ByteArray(0))).isNull()
        assertThat(osdbHash(0L, ByteArray(0), ByteArray(0))).isNull()
    }

    @Test fun `all-zero chunks hash to the filesize itself`() {
        // With zero head+tail, hash == fileSize rendered as 16 lowercase hex.
        val size = 12909756L
        val zeros = ByteArray(OSDB_CHUNK_BYTES)
        assertThat(osdbHash(size, zeros, zeros)).isEqualTo("0000000000c4fcbc")
    }

    @Test fun `little-endian word summation`() {
        // One 8-byte word 0x0102030405060708 LE in head, zero tail, size 0x20000 (128 KiB).
        val head = ByteArray(OSDB_CHUNK_BYTES)
        // LE bytes of 0x0102030405060708:
        val word = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        word.copyInto(head, 0)
        val tail = ByteArray(OSDB_CHUNK_BYTES)
        val size = 0x20000L
        // expected = 0x20000 + 0x0102030405060708 = 0x0102030405080708
        assertThat(osdbHash(size, head, tail)).isEqualTo("0102030405080708")
    }

    @Test fun `unsigned 64-bit wraparound`() {
        // head word = 0xFFFFFFFFFFFFFFFF, size = 0x20002 → sum wraps mod 2^64 to 0x20001.
        val head = ByteArray(OSDB_CHUNK_BYTES)
        byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1).copyInto(head, 0)
        val tail = ByteArray(OSDB_CHUNK_BYTES)
        assertThat(osdbHash(0x20002L, head, tail)).isEqualTo("0000000000020001")
    }

    @Test fun `chunks must be exactly OSDB_CHUNK_BYTES`() {
        // Defensive: a wrong-sized chunk is treated as no-hash rather than a partial read.
        assertThat(osdbHash(1_000_000L, ByteArray(10), ByteArray(OSDB_CHUNK_BYTES))).isNull()
    }
}
