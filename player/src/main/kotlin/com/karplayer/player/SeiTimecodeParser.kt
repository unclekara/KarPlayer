package com.karplayer.player

/**
 * Streaming scanner for the KarRelay SEI timecode NALs.
 *
 * The relay injects a separate TS packet just before every PUSI-marked I-frame
 * that carries an `SEI user_data_unregistered` NAL with this layout:
 *
 * ```
 * 00 00 00 01                   Annex-B start code
 * 06                            nal_unit_type = 6 (SEI)
 * 05                            payloadType = user_data_unregistered
 * 18                            payloadSize = 24
 * 4B 61 72 53 45 49 2D 54 53 59 4E 43 00 00 00 00   ("KarSEI-TSYNC\0\0\0\0")
 * <8 bytes big-endian timestamp_us>
 * 80                            RBSP stop bit
 * ```
 *
 * We don't bother re-parsing the surrounding NAL framing — the 16-byte UUID
 * is unique enough that a plain byte-substring search inside the SRT/MPEG-TS
 * byte stream is reliable. The parser maintains a small carry between
 * subsequent [feed] calls so a UUID split across two SRT reads is still
 * detected.
 *
 * Thread-safety: instances are stateful, intended to be owned by a single
 * DataSource. Not safe for concurrent use across threads.
 */
internal class SeiTimecodeParser {

    private val uuid = UUID_TSYNC
    private val timestampSize = 8
    private val maxCarry = uuid.size + timestampSize

    /**
     * Bytes carried over from the tail of the previous feed, in case a UUID
     * starts there and finishes in the next read. Capped at [maxCarry]
     * — searching further back would risk re-reporting an already-found UUID.
     */
    private var carry: ByteArray = ByteArray(0)

    /**
     * Feeds [len] bytes from [buf] starting at [off]. Returns every SEI
     * timestamp (UTC microseconds) found in the combined stream
     * (carry + this chunk). The returned list is in arrival order; it is
     * usually empty (SEI is only injected once per I-frame).
     */
    fun feed(buf: ByteArray, off: Int, len: Int): List<Long> {
        if (len <= 0) return emptyList()

        val haystack: ByteArray = if (carry.isEmpty()) {
            ByteArray(len).also { System.arraycopy(buf, off, it, 0, len) }
        } else {
            val combined = ByteArray(carry.size + len)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(buf, off, combined, carry.size, len)
            combined
        }

        val results = ArrayList<Long>()
        var searchFrom = 0
        while (true) {
            val idx = indexOf(haystack, uuid, searchFrom)
            if (idx < 0) break
            val tsStart = idx + uuid.size
            if (tsStart + timestampSize <= haystack.size) {
                results.add(readBigEndianLong(haystack, tsStart))
                // Continue past this match to find subsequent SEIs in the
                // same buffer (rare, but cheap to check).
                searchFrom = tsStart + timestampSize
            } else {
                // UUID is in the buffer but the 8-byte timestamp is not yet
                // complete — leave it for the next feed via carry.
                break
            }
        }

        carry = if (haystack.size > maxCarry) {
            haystack.copyOfRange(haystack.size - maxCarry, haystack.size)
        } else {
            haystack
        }

        return results
    }

    /** Reset internal carry — call on (re)connect to drop stale state. */
    fun reset() {
        carry = ByteArray(0)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val last = haystack.size - needle.size
        outer@ for (i in from..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readBigEndianLong(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return v
    }

    companion object {
        /** The 16-byte marker the relay writes between the SEI header and
         *  the timestamp payload. */
        val UUID_TSYNC: ByteArray = byteArrayOf(
            0x4B, 0x61, 0x72, 0x53, 0x45, 0x49, 0x2D, 0x54,
            0x53, 0x59, 0x4E, 0x43, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
