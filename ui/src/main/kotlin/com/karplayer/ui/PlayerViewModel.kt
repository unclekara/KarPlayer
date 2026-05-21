package com.karplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.karplayer.player.MediaInfo
import com.karplayer.player.PlayerManager
import com.karplayer.player.PlayerState
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

    fun onAppResumed() = playerManager.onAppResumed()

    private val _config = MutableStateFlow(ConnectionConfig())
    val connectionConfig: StateFlow<ConnectionConfig> = _config.asStateFlow()

    fun setConfig(cfg: ConnectionConfig) { _config.value = cfg }

    fun connect(cfg: ConnectionConfig) {
        _config.value = cfg
        playerManager.connect(
            host = cfg.host,
            port = cfg.port,
            options = cfg.toSrtOptions(),
            useSoftwareDecoder = cfg.useSoftwareDecoder
        )
    }

    fun disconnect() { playerManager.disconnect() }

    class Factory(private val playerManager: PlayerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(playerManager) as T
    }
}
