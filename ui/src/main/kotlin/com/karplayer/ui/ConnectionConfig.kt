package com.karplayer.ui

import android.content.Context
import com.karplayer.srt.SrtMode
import com.karplayer.srt.SrtOptions

/**
 * maxBandwidthMode:
 *   AUTO  → SRT-managed bandwidth (SRTO_MAXBW = -1)
 *   UNLIM → no cap            (SRTO_MAXBW = 0)
 *   FIXED → use [maxBandwidthMbps]
 */
enum class MaxBwMode { AUTO, UNLIM, FIXED }

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 9000,
    val latencyMs: Int = 120,
    val streamId: String = "",
    val mode: SrtMode = SrtMode.CALLER,
    val maxBwMode: MaxBwMode = MaxBwMode.AUTO,
    val maxBandwidthMbps: Int = 50,
    val passphrase: String = "",
    val pbkeyLen: Int = 0,
    val useSoftwareDecoder: Boolean = false
) {
    fun toSrtOptions(): SrtOptions = SrtOptions(
        latency = latencyMs,
        mode = mode,
        streamId = streamId,
        maxBandwidth = when (maxBwMode) {
            MaxBwMode.AUTO -> -1L
            MaxBwMode.UNLIM -> 0L
            MaxBwMode.FIXED -> maxBandwidthMbps.toLong() * 1_000_000L / 8L
        },
        passphrase = passphrase,
        pbkeyLen = pbkeyLen
    )
}

object ConnectionConfigStore {
    private const val PREFS = "karplayer_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_LATENCY = "latency"
    private const val KEY_STREAM_ID = "stream_id"
    private const val KEY_MODE = "mode"
    private const val KEY_MAXBW_MODE = "maxbw_mode"
    private const val KEY_MAXBW_MBPS = "maxbw_mbps"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_PBKEYLEN = "pbkeylen"
    private const val KEY_SOFTWARE_DECODER = "software_decoder"

    fun load(context: Context): ConnectionConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ConnectionConfig(
            host = p.getString(KEY_HOST, "") ?: "",
            port = p.getInt(KEY_PORT, 9000),
            latencyMs = p.getInt(KEY_LATENCY, 120),
            streamId = p.getString(KEY_STREAM_ID, "") ?: "",
            mode = runCatching {
                SrtMode.valueOf(p.getString(KEY_MODE, SrtMode.CALLER.name)!!)
            }.getOrDefault(SrtMode.CALLER),
            maxBwMode = runCatching {
                MaxBwMode.valueOf(p.getString(KEY_MAXBW_MODE, MaxBwMode.AUTO.name)!!)
            }.getOrDefault(MaxBwMode.AUTO),
            maxBandwidthMbps = p.getInt(KEY_MAXBW_MBPS, 50),
            passphrase = p.getString(KEY_PASSPHRASE, "") ?: "",
            pbkeyLen = p.getInt(KEY_PBKEYLEN, 0),
            useSoftwareDecoder = p.getBoolean(KEY_SOFTWARE_DECODER, false)
        )
    }

    fun save(context: Context, cfg: ConnectionConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_HOST, cfg.host)
            putInt(KEY_PORT, cfg.port)
            putInt(KEY_LATENCY, cfg.latencyMs)
            putString(KEY_STREAM_ID, cfg.streamId)
            putString(KEY_MODE, cfg.mode.name)
            putString(KEY_MAXBW_MODE, cfg.maxBwMode.name)
            putInt(KEY_MAXBW_MBPS, cfg.maxBandwidthMbps)
            putString(KEY_PASSPHRASE, cfg.passphrase)
            putInt(KEY_PBKEYLEN, cfg.pbkeyLen)
            putBoolean(KEY_SOFTWARE_DECODER, cfg.useSoftwareDecoder)
            apply()
        }
    }
}
