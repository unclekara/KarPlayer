package com.karplayer.srt

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Media3 DataSource backed by an SRT socket. Built for live MPEG-TS — length is
 * always C.LENGTH_UNSET, reads block until data arrives or the socket closes.
 *
 * URI form: srt://host:port  (no path expected, ignored if present).
 */
class SrtDataSource(
    private val options: SrtOptions
) : BaseDataSource(/* isNetwork = */ true) {

    private var socket: SrtSocket? = null
    private var openedUri: Uri? = null
    private var opened: Boolean = false

    // SrtDataSource is constructed by the factory *before* open() runs. The
    // socket — and therefore the underlying stats flow — doesn't exist yet.
    // We own a stable StateFlow that the player layer can subscribe to right
    // after createDataSource(), and we bridge socket.stats into it on open().
    private val _stats = MutableStateFlow(SrtStats())
    val stats: StateFlow<SrtStats> = _stats.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var bridgeJob: Job? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val host = uri.host ?: throw IOException("SRT URI missing host: $uri")
        val port = uri.port.takeIf { it > 0 } ?: throw IOException("SRT URI missing port: $uri")

        val s = SrtSocket()
        try {
            s.open(host, port, options)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        openedUri = uri
        opened = true

        bridgeJob = scope.launch {
            s.stats.collect { _stats.value = it }
        }

        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val s = socket ?: return C.RESULT_END_OF_INPUT
        val n = try { s.read(buffer, offset, length) } catch (e: IOException) { throw e }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        bridgeJob?.cancel(); bridgeJob = null
        scope.cancel()
        socket?.close()
        socket = null
        openedUri = null
    }
}
