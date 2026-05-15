package com.karplayer.srt

enum class SrtMode(internal val nativeCode: Int) {
    CALLER(0), LISTENER(1), RENDEZVOUS(2)
}

/**
 * Subset of libsrt options relevant for a low-latency MPEG-TS receiver.
 *
 * - [latency]      target receiver-side TSBPD buffer (ms)
 * - [maxBandwidth] -1 = AUTO (let SRT negotiate), 0 = unlimited, >0 = bytes/s
 * - [passphrase]   AES key material; empty disables encryption
 * - [pbkeyLen]     0 = peer-driven, 16/24/32 = AES-128/192/256
 */
data class SrtOptions(
    val latency: Int = 120,
    val maxBandwidth: Long = -1L,
    val inputBandwidth: Long = 0L,
    val mode: SrtMode = SrtMode.CALLER,
    val streamId: String = "",
    val timeout: Int = 3000,
    val passphrase: String = "",
    val pbkeyLen: Int = 0
)
