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

/**
 * SyncMode:
 *   OFF         → legacy behaviour, large buffer, no speed control
 *   LOW_LATENCY → smaller ExoPlayer buffer + live-edge speed control
 *   SEI_SYNC    → frame-accurate sync to KarRelay wall-clock SEI marks
 */
enum class SyncMode { OFF, LOW_LATENCY, SEI_SYNC }

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
    val useSoftwareDecoder: Boolean = false,
    val syncMode: SyncMode = SyncMode.OFF,
    val maxBufferMs: Int = 400,         // used when syncMode = LOW_LATENCY
    val targetLagMs: Int = 250,         // used when syncMode = SEI_SYNC
    val syncDeadbandMs: Int = 50,       // SEI_SYNC speed deadband
    val maxSpeedAdjustPct: Int = 5,     // SEI_SYNC max speed deviation, 0..50
    val relayHttpPort: Int = 8484,      // KarRelay service (web) port
    /** ISO-639 code of the user's last picked audio language. Empty /
     *  null = let the demuxer pick. Surface set by PlayerScreen's
     *  AUDIO bottom sheet; applied at connect via
     *  TrackSelectionParameters.setPreferredAudioLanguage. */
    val preferredAudioLanguage: String? = null,
    /** Kiosk / digital-signage mode. Hides every overlay (stats, bottom
     *  bar, spinners, error text), forces immersive fullscreen and keeps
     *  the screen black while no signal is arriving. Reconnect becomes
     *  unbounded so the player waits on a black screen indefinitely
     *  instead of bailing out to the menu. */
    val kioskMode: Boolean = false,
    /** Kiosk autostart: connect on app launch without waiting for a tap,
     *  so an unattended screen comes back on its own after a power cycle
     *  or an app restart. Only honoured while [kioskMode] is on — see
     *  KarPlayerNavRoot. */
    val kioskAutoStart: Boolean = false,
) {
    /** True when the form holds enough to attempt a session. Mirrors the
     *  Connect button's own validation; used by kiosk autostart, which has
     *  no user around to correct a half-filled config. */
    val isConnectable: Boolean
        get() = (mode == SrtMode.LISTENER || host.isNotBlank()) && port in 1..65535

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
    private const val KEY_SYNC_MODE = "sync_mode"
    private const val KEY_MAX_BUFFER_MS = "max_buffer_ms"
    private const val KEY_TARGET_LAG_MS = "target_lag_ms"
    private const val KEY_SYNC_DEADBAND_MS = "sync_deadband_ms"
    private const val KEY_MAX_SPEED_ADJUST_PCT = "max_speed_adjust_pct"
    private const val KEY_RELAY_HTTP_PORT = "relay_http_port"
    private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
    private const val KEY_KIOSK_MODE = "kiosk_mode"
    private const val KEY_KIOSK_AUTOSTART = "kiosk_autostart"

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
            useSoftwareDecoder = p.getBoolean(KEY_SOFTWARE_DECODER, false),
            syncMode = runCatching {
                SyncMode.valueOf(p.getString(KEY_SYNC_MODE, SyncMode.OFF.name)!!)
            }.getOrDefault(SyncMode.OFF),
            maxBufferMs = p.getInt(KEY_MAX_BUFFER_MS, 400),
            targetLagMs = p.getInt(KEY_TARGET_LAG_MS, 250),
            syncDeadbandMs = p.getInt(KEY_SYNC_DEADBAND_MS, 50),
            maxSpeedAdjustPct = p.getInt(KEY_MAX_SPEED_ADJUST_PCT, 5),
            relayHttpPort = p.getInt(KEY_RELAY_HTTP_PORT, 8484),
            preferredAudioLanguage = p.getString(KEY_PREFERRED_AUDIO_LANG, null)?.ifBlank { null },
            kioskMode = p.getBoolean(KEY_KIOSK_MODE, false),
            kioskAutoStart = p.getBoolean(KEY_KIOSK_AUTOSTART, false)
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
            putString(KEY_SYNC_MODE, cfg.syncMode.name)
            putInt(KEY_MAX_BUFFER_MS, cfg.maxBufferMs)
            putInt(KEY_TARGET_LAG_MS, cfg.targetLagMs)
            putInt(KEY_SYNC_DEADBAND_MS, cfg.syncDeadbandMs)
            putInt(KEY_MAX_SPEED_ADJUST_PCT, cfg.maxSpeedAdjustPct)
            putInt(KEY_RELAY_HTTP_PORT, cfg.relayHttpPort)
            putString(KEY_PREFERRED_AUDIO_LANG, cfg.preferredAudioLanguage)
            putBoolean(KEY_KIOSK_MODE, cfg.kioskMode)
            putBoolean(KEY_KIOSK_AUTOSTART, cfg.kioskAutoStart)
            apply()
        }
    }
}
