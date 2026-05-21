package com.karplayer.srt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class SrtSocket {

    private val handle = AtomicLong(INVALID)
    private val statsScope = CoroutineScope(Dispatchers.IO)
    private var statsJob: Job? = null
    private val statsBuf = DoubleArray(8)

    // SRT live mode delivers one message per srt_recvmsg call (up to
    // SRTO_PAYLOADSIZE = 1316 bytes) and rejects calls whose buffer is
    // smaller than the payload. Media3 extractors call DataSource.read with
    // arbitrary lengths (down to a few bytes during sniff). We absorb that
    // mismatch by always pulling a full message into this buffer and serving
    // the caller incrementally from it.
    private val rxBuf = ByteArray(SRT_MAX_PAYLOAD)
    private var rxPos: Int = 0
    private var rxLimit: Int = 0

    private val _stats = MutableStateFlow(SrtStats())
    val stats: StateFlow<SrtStats> = _stats.asStateFlow()

    val isOpen: Boolean get() = handle.get() != INVALID

    @Throws(IOException::class)
    fun open(host: String, port: Int, options: SrtOptions) {
        if (isOpen) throw IOException("SrtSocket already open")
        val h = SrtNative.nativeCreate()
        if (h < 0) throw IOException("nativeCreate failed (libsrt missing or stub mode?)")

        val result = SrtNative.nativeConnect(
            handle = h,
            host = host,
            port = port,
            latencyMs = options.latency,
            maxBwBps = options.maxBandwidth,
            inputBwBps = options.inputBandwidth,
            mode = options.mode.nativeCode,
            streamId = options.streamId,
            timeoutMs = options.timeout,
            passphrase = options.passphrase,
            pbkeylen = options.pbkeyLen
        )
        // Negative return = error code; non-negative = the actual handle to
        // use (same as `h` for caller/rendezvous, the accepted-peer handle
        // for listener — the listener socket itself is closed natively).
        if (result < 0) {
            SrtNative.nativeClose(h)
            throw IOException("SRT connect failed rc=$result host=$host:$port mode=${options.mode}")
        }
        handle.set(result)
        startStatsPolling()
    }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val h = handle.get()
        if (h == INVALID) return -1
        if (length <= 0) return 0

        if (rxPos >= rxLimit) {
            val n = SrtNative.nativeRead(h, rxBuf, 0, rxBuf.size)
            if (n < 0 && n != SrtNative.ERR_STUB) {
                throw IOException("SRT read error rc=$n")
            }
            if (n <= 0) return n
            rxPos = 0
            rxLimit = n
        }

        val available = rxLimit - rxPos
        val toCopy = if (length < available) length else available
        System.arraycopy(rxBuf, rxPos, buffer, offset, toCopy)
        rxPos += toCopy
        return toCopy
    }

    fun close() {
        val h = handle.getAndSet(INVALID)
        statsJob?.cancel(); statsJob = null
        statsScope.cancel()
        if (h != INVALID) SrtNative.nativeClose(h)
    }

    private fun startStatsPolling() {
        statsJob = statsScope.launch {
            while (isActive && isOpen) {
                val h = handle.get()
                if (h == INVALID) break
                val rc = SrtNative.nativeGetStats(h, statsBuf)
                if (rc == SrtNative.OK) {
                    _stats.value = SrtStats.fromNative(statsBuf)
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val INVALID = -1L
        const val STATS_INTERVAL_MS = 500L
        // Matches libsrt default SRTO_PAYLOADSIZE for live mode (1316). We
        // size the buffer slightly larger to be defensive if the peer ever
        // negotiates a bigger payload.
        const val SRT_MAX_PAYLOAD = 1500
    }
}
