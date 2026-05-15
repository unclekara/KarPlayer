package com.karplayer.srt

internal object SrtNative {

    init { System.loadLibrary("karplayer_srt") }

    const val OK = 0
    const val ERR_STUB = -1000
    const val ERR_CREATE = -1001
    const val ERR_SETOPT = -1002
    const val ERR_CONNECT = -1003
    const val ERR_INVALID = -1004
    const val ERR_READ = -1005

    external fun nativeCreate(): Long

    external fun nativeConnect(
        handle: Long,
        host: String,
        port: Int,
        latencyMs: Int,
        maxBwBps: Long,
        inputBwBps: Long,
        mode: Int,
        streamId: String,
        timeoutMs: Int,
        passphrase: String,
        pbkeylen: Int
    ): Int

    external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeClose(handle: Long)
    external fun nativeGetStats(handle: Long, out: DoubleArray): Int
}
