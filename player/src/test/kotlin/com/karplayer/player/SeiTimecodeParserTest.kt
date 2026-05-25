package com.karplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeiTimecodeParserTest {

    private fun timestampBytes(ts: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) {
            out[i] = ((ts shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun sei(ts: Long): ByteArray =
        SeiTimecodeParser.UUID_TSYNC + timestampBytes(ts)

    @Test
    fun finds_single_uuid_in_one_buffer() {
        val parser = SeiTimecodeParser()
        val payload = ByteArray(64) + sei(1_700_000_000_123_456L) + ByteArray(32)
        val found = parser.feed(payload, 0, payload.size)
        assertEquals(listOf(1_700_000_000_123_456L), found)
    }

    @Test
    fun handles_uuid_split_across_two_feeds() {
        val parser = SeiTimecodeParser()
        val full = ByteArray(40) + sei(42L) + ByteArray(40)
        // Cut right in the middle of the UUID.
        val splitAt = 40 + 8
        val first = parser.feed(full, 0, splitAt)
        assertTrue("first half must not yield a match", first.isEmpty())
        val second = parser.feed(full, splitAt, full.size - splitAt)
        assertEquals(listOf(42L), second)
    }

    @Test
    fun handles_uuid_present_but_timestamp_split() {
        val parser = SeiTimecodeParser()
        val full = ByteArray(16) + sei(123_456_789L) + ByteArray(16)
        // Cut after UUID but before timestamp end.
        val splitAt = 16 + SeiTimecodeParser.UUID_TSYNC.size + 3
        val first = parser.feed(full, 0, splitAt)
        assertTrue(first.isEmpty())
        val second = parser.feed(full, splitAt, full.size - splitAt)
        assertEquals(listOf(123_456_789L), second)
    }

    @Test
    fun multiple_uuids_in_one_buffer() {
        val parser = SeiTimecodeParser()
        val payload = ByteArray(8) + sei(100L) + ByteArray(16) + sei(200L) + ByteArray(4)
        val found = parser.feed(payload, 0, payload.size)
        assertEquals(listOf(100L, 200L), found)
    }

    @Test
    fun no_uuid_returns_empty() {
        val parser = SeiTimecodeParser()
        val payload = ByteArray(2048) { (it and 0xFF).toByte() }
        // Make sure we don't accidentally include the marker.
        // (Random byte pattern is statistically not going to match a 16-byte UUID.)
        val found = parser.feed(payload, 0, payload.size)
        assertTrue(found.isEmpty())
    }

    @Test
    fun reset_drops_carry() {
        val parser = SeiTimecodeParser()
        val full = ByteArray(8) + sei(7L)
        // Feed everything up to just before timestamp end so carry holds half.
        val firstLen = 8 + SeiTimecodeParser.UUID_TSYNC.size + 4
        parser.feed(full, 0, firstLen)
        parser.reset()
        val rest = parser.feed(full, firstLen, full.size - firstLen)
        // After reset the trailing partial bytes alone aren't enough to
        // match — confirms carry was dropped.
        assertTrue(rest.isEmpty())
    }
}
