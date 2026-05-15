package com.karplayer.srt

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtStatsTest {

    @Test
    fun fromNative_mapsIndicesCorrectly() {
        val buf = doubleArrayOf(42.0, 8200.0, 0.5, 2.3, 1000.0, 5.0, 1.0, 0.0)
        val s = SrtStats.fromNative(buf)

        assertEquals(42.0, s.rttMs, 0.0)
        assertEquals(8200.0, s.bitrateKbps, 0.0)
        assertEquals(0.5, s.packetLossPct, 0.0)
        assertEquals(2.3, s.jitterMs, 0.0)
        assertEquals(1000L, s.receivedPackets)
        assertEquals(5L, s.lostPackets)
        assertEquals(1L, s.retransmittedPackets)
    }

    @Test
    fun defaults_areZero() {
        val s = SrtStats()
        assertEquals(0.0, s.rttMs, 0.0)
        assertEquals(0L, s.receivedPackets)
    }
}
