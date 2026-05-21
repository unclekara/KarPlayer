package com.karplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.karplayer.srt.SrtDataSource
import com.karplayer.srt.SrtOptions
import com.karplayer.srt.SrtStats
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

enum class PlayerState { IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, ERROR }

private data class Endpoint(val host: String, val port: Int, val options: SrtOptions)

class PlayerManager(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    val player: ExoPlayer = ExoPlayer.Builder(context, LowLatencyRenderersFactory(context))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 100,
                    /* maxBufferMs = */ 1500,
                    /* bufferForPlaybackMs = */ 0,
                    /* bufferForPlaybackAfterRebufferMs = */ 50
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()

    private val _state = MutableStateFlow(PlayerState.IDLE)
    val playerState: StateFlow<PlayerState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SrtStats())
    val stats: StateFlow<SrtStats> = _stats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeDataSource: SrtDataSource? = null

    /** Latest endpoint connected to — used by the reconnect loop and resume(). */
    private var lastEndpoint: Endpoint? = null

    /** True after the user pressed Connect; false after Disconnect. While set,
     *  errors trigger automatic reconnect attempts. */
    private var autoReconnectEnabled: Boolean = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val current = _state.value
                val mapped = when (state) {
                    Player.STATE_IDLE -> when (current) {
                        PlayerState.CONNECTING, PlayerState.RECONNECTING -> return
                        else -> PlayerState.IDLE
                    }
                    Player.STATE_BUFFERING -> PlayerState.BUFFERING
                    Player.STATE_READY -> PlayerState.PLAYING
                    Player.STATE_ENDED -> PlayerState.IDLE
                    else -> PlayerState.IDLE
                }
                _state.value = mapped
            }

            override fun onPlayerError(error: PlaybackException) {
                _lastError.value = error.message ?: error.errorCodeName
                if (autoReconnectEnabled && lastEndpoint != null) {
                    scheduleReconnect()
                } else {
                    _state.value = PlayerState.ERROR
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                var video: Format? = null
                var audio: Format? = null
                for (group in tracks.groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val f = group.getTrackFormat(i)
                        when {
                            video == null && MimeTypes.isVideo(f.sampleMimeType) -> video = f
                            audio == null && MimeTypes.isAudio(f.sampleMimeType) -> audio = f
                        }
                    }
                }
                _mediaInfo.value = _mediaInfo.value.copy(
                    videoCodec = video?.sampleMimeType?.removePrefix("video/")?.uppercase(),
                    videoWidth = video?.width ?: 0,
                    videoHeight = video?.height ?: 0,
                    videoFrameRate = video?.frameRate ?: 0f,
                    audioCodec = audio?.sampleMimeType?.removePrefix("audio/")?.uppercase(),
                    audioSampleRate = audio?.sampleRate ?: 0,
                    audioChannels = audio?.channelCount ?: 0
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _mediaInfo.value = _mediaInfo.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height
                )
            }
        })
    }

    fun connect(host: String, port: Int, options: SrtOptions) {
        cancelReconnect()
        teardownCurrentSession()
        lastEndpoint = Endpoint(host, port, options)
        autoReconnectEnabled = true
        _lastError.value = null
        _reconnectAttempt.value = 0
        startSession()
    }

    fun disconnect() {
        autoReconnectEnabled = false
        cancelReconnect()
        teardownCurrentSession()
        lastEndpoint = null
        _state.value = PlayerState.IDLE
        _stats.value = SrtStats()
        _mediaInfo.value = MediaInfo()
        _reconnectAttempt.value = 0
    }

    /** Called by UI on Lifecycle.Event.ON_RESUME.
     *
     *  We always force a full restart of the SRT session — both when it died
     *  silently in the background and when it kept running. For a live stream
     *  the latter case is actually worse: the SRT receiver buffer keeps
     *  filling, the video sink was detached, and on resume the player would
     *  resume from the stale buffered position, accumulating latency every
     *  time the user backgrounds the app. Reconnecting drops everything and
     *  jumps back to the live edge. */
    fun onAppResumed() {
        val ep = lastEndpoint ?: return
        if (!autoReconnectEnabled) return
        cancelReconnect()
        teardownCurrentSession()
        _state.value = PlayerState.CONNECTING
        _reconnectAttempt.value = 0
        startSession(ep)
    }

    fun setSurface(surface: Surface?) {
        mainHandler.post { player.setVideoSurface(surface) }
    }

    fun release() {
        autoReconnectEnabled = false
        cancelReconnect()
        statsJob?.cancel()
        scope.cancel()
        mainHandler.post { player.release() }
    }

    private fun startSession(endpoint: Endpoint? = lastEndpoint) {
        val ep = endpoint ?: return
        val starting = _state.value == PlayerState.RECONNECTING
        if (!starting) _state.value = PlayerState.CONNECTING

        val dsFactory = SrtDataSourceFactory(ep.options) { ds ->
            activeDataSource = ds
            attachStatsFlow(ds)
        }
        val mediaSource = SrtMediaSourceFactory(dsFactory)
            .create(Uri.parse("srt://${ep.host}:${ep.port}"))

        mainHandler.post {
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Schedules a backoff-delayed reconnect attempt. Loops until the user
     *  pressed Disconnect or a session reaches PLAYING. */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val ep = lastEndpoint ?: return
        _state.value = PlayerState.RECONNECTING

        reconnectJob = scope.launch {
            while (isActive && autoReconnectEnabled && lastEndpoint != null) {
                val attempt = _reconnectAttempt.value + 1
                _reconnectAttempt.value = attempt
                val delayMs = backoffDelay(attempt)
                delay(delayMs)
                if (!autoReconnectEnabled) break
                teardownCurrentSession()
                startSession(ep)
                // Give the player a chance to either reach BUFFERING/PLAYING
                // or fail with onPlayerError, which will re-trigger this loop
                // via scheduleReconnect(). We exit this iteration either way.
                delay(2000)
                if (_state.value == PlayerState.PLAYING ||
                    _state.value == PlayerState.BUFFERING
                ) {
                    _reconnectAttempt.value = 0
                    break
                }
            }
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        // 200ms, 400ms, 800ms, 1600ms, 3200ms, 5000ms (cap)
        val ms = 200L shl (attempt - 1).coerceIn(0, 5)
        return ms.coerceAtMost(5000L)
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun teardownCurrentSession() {
        statsJob?.cancel(); statsJob = null
        activeDataSource = null
        mainHandler.post {
            player.stop()
            player.clearMediaItems()
        }
        _stats.value = SrtStats()
    }

    private fun attachStatsFlow(ds: SrtDataSource) {
        statsJob?.cancel()
        statsJob = scope.launch {
            ds.stats.collect { _stats.value = it }
        }
    }
}
