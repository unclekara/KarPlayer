package com.karplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.karplayer.player.MediaInfo
import com.karplayer.player.PlayerManager
import com.karplayer.player.PlayerState
import com.karplayer.player.PlayerSyncController
import com.karplayer.srt.SrtStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerViewModel(
    val playerManager: PlayerManager
) : ViewModel() {

    val playerState: StateFlow<PlayerState> = playerManager.playerState
    val stats: StateFlow<SrtStats> = playerManager.stats
    val lastError: StateFlow<String?> = playerManager.lastError
    val mediaInfo: StateFlow<MediaInfo> = playerManager.mediaInfo
    val reconnectAttempt: StateFlow<Int> = playerManager.reconnectAttempt
    val isInPip: StateFlow<Boolean> = playerManager.isInPip
    val syncState: StateFlow<PlayerSyncController.State?> = playerManager.syncState
    val measuredLagMs: StateFlow<Long?> = playerManager.measuredLagMs

    fun onAppResumed() = playerManager.onAppResumed()

    private val _config = MutableStateFlow(ConnectionConfig())
    val connectionConfig: StateFlow<ConnectionConfig> = _config.asStateFlow()

    fun setConfig(cfg: ConnectionConfig) { _config.value = cfg }

    fun connect(cfg: ConnectionConfig) {
        _config.value = cfg
        val maxBufferMs: Int
        val seiConfig: PlayerSyncController.SeiSyncConfig?
        val liveEdgeTargetMs: Int
        when (cfg.syncMode) {
            SyncMode.OFF -> {
                maxBufferMs = 1500
                seiConfig = null
                liveEdgeTargetMs = 0
            }
            SyncMode.LOW_LATENCY -> {
                maxBufferMs = cfg.maxBufferMs
                seiConfig = null
                // Cap the back-buffer to half the user's target so the
                // periodic seek-to-live-edge trims accumulated lag.
                liveEdgeTargetMs = (cfg.maxBufferMs / 2).coerceAtLeast(100)
            }
            SyncMode.SEI_SYNC -> {
                maxBufferMs = 600
                seiConfig = PlayerSyncController.SeiSyncConfig(
                    targetLagMs = cfg.targetLagMs.toLong(),
                    deadbandMs = cfg.syncDeadbandMs.toLong(),
                    maxSpeedAdjustPct = cfg.maxSpeedAdjustPct
                )
                liveEdgeTargetMs = 0
            }
        }
        playerManager.connect(
            host = cfg.host,
            port = cfg.port,
            options = cfg.toSrtOptions(),
            useSoftwareDecoder = cfg.useSoftwareDecoder,
            maxBufferMs = maxBufferMs,
            seiSync = seiConfig,
            liveEdgeTargetMs = liveEdgeTargetMs
        )
    }

    fun disconnect() { playerManager.disconnect() }

    class Factory(private val playerManager: PlayerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(playerManager) as T
    }
}
