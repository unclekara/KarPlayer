package com.karplayer.srt

data class SrtStats(
    val rttMs: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val packetLossPct: Double = 0.0,
    val jitterMs: Double = 0.0,
    val receivedPackets: Long = 0L,
    val lostPackets: Long = 0L,
    val retransmittedPackets: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        internal fun fromNative(buf: DoubleArray): SrtStats = SrtStats(
            rttMs = buf[0],
            bitrateKbps = buf[1],
            packetLossPct = buf[2],
            jitterMs = buf[3],
            receivedPackets = buf[4].toLong(),
            lostPackets = buf[5].toLong(),
            retransmittedPackets = buf[6].toLong()
        )
    }
}
