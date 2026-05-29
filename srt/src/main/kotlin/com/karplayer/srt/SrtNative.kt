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

    // SRT rejection reason codes set by the KarRelay listener and read
    // back via srt_getrejectreason(). Values match libsrt access_control.h.
    const val REJ_NONE = 0
    const val REJX_OVERLOAD = 1402   // free-tier viewer limit reached
    const val REJX_FORBIDDEN = 1403  // stream id not allowed for this source

    external fun nativeCreate(): Long

    /**
     * Returns the SRT socket handle to use for subsequent read/close, or a
     * negative KP_ERR_* code on failure. In LISTENER mode the returned handle
     * is the *accepted* peer socket (the listener handle is closed natively).
     */
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
    ): Long

    external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeClose(handle: Long)
    external fun nativeGetStats(handle: Long, out: DoubleArray): Int

    /** SRT rejection reason from the most recent failed CALLER connect
     *  (one of the REJ / REJX codes above), or [REJ_NONE] if none. */
    external fun nativeLastRejectReason(): Int
}
