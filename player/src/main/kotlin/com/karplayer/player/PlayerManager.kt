package com.karplayer.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @Volatile private var useSoftwareDecoder: Boolean = false

    private val wifiLock: WifiManager.WifiLock = run {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "karplayer:srt-rx").apply {
            setReferenceCounted(false)
        }
    }

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        @Volatile private var hadLoss = false
        override fun onLost(network: Network) { hadLoss = true }
        override fun onAvailable(network: Network) {
            if (!hadLoss) return
            hadLoss = false
            if (!autoReconnectEnabled || lastEndpoint == null) return
            mainHandler.post {
                val s = _state.value
                if (s == PlayerState.RECONNECTING || s == PlayerState.ERROR) {
                    cancelReconnect()
                    _reconnectAttempt.value = 0
                    scheduleReconnect()
                }
            }
        }
    }

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

    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    /** Mirrors the active [PlayerSyncController]'s state. Null state means
     *  SEI sync is disabled for the current session. */
    private val _syncState = MutableStateFlow<PlayerSyncController.State?>(null)
    val syncState: StateFlow<PlayerSyncController.State?> = _syncState.asStateFlow()

    private val _measuredLagMs = MutableStateFlow<Long?>(null)
    val measuredLagMs: StateFlow<Long?> = _measuredLagMs.asStateFlow()

    @Volatile private var recentlyInPip: Boolean = false
    private var clearPipGuardJob: Job? = null

    fun setInPipMode(value: Boolean) {
        _isInPip.value = value
        recentlyInPip = true
        clearPipGuardJob?.cancel()
        clearPipGuardJob = scope.launch {
            delay(PIP_GUARD_MS)
            recentlyInPip = false
        }
    }

    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var clockSyncJob: Job? = null
    private var syncStaleJob: Job? = null
    private var syncMirrorJob: Job? = null
    private var liveEdgeJob: Job? = null
    private var activeDataSource: SrtDataSource? = null
    private var syncController: PlayerSyncController? = null
    private var liveEdgeTargetMs: Int = 0

    private var lastEndpoint: Endpoint? = null
    private var autoReconnectEnabled: Boolean = false

    // Buffer / sync configuration of the last connect call. Used to decide
    // whether the ExoPlayer instance must be rebuilt before a new session
    // (the LoadControl is immutable on a built ExoPlayer).
    private var currentMaxBufferMs: Int = 1500
    private var pendingSurface: Surface? = null

    // NOTE: declared *before* _player because buildPlayer() references it.
    // Kotlin class init order is top-to-bottom, so a `private val` defined
    // later in the file is still null when an earlier `val` runs its
    // initializer — the JVM-level field hasn't been assigned yet.
    private val playerListener = object : Player.Listener {
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
    }

    private var _player: ExoPlayer = buildPlayer(maxBufferMs = currentMaxBufferMs)
    /** Current ExoPlayer instance. The reference changes when the LoadControl
     *  needs to be reconfigured (i.e., when the user picks a different sync
     *  mode that demands a different buffer ceiling). Callers should re-read
     *  this property rather than caching it. */
    val player: ExoPlayer get() = _player

    init {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
    }

    fun connect(
        host: String,
        port: Int,
        options: SrtOptions,
        useSoftwareDecoder: Boolean = false,
        maxBufferMs: Int = 1500,
        seiSync: PlayerSyncController.SeiSyncConfig? = null,
        liveEdgeTargetMs: Int = 0
    ) {
        Log.i(
            TAG,
            "connect: $host:$port maxBufferMs=$maxBufferMs " +
                    "seiSync=${seiSync != null} liveEdgeTargetMs=$liveEdgeTargetMs " +
                    "srtLatency=${options.latency} sw=$useSoftwareDecoder"
        )
        cancelReconnect()
        clockSyncJob?.cancel(); clockSyncJob = null
        teardownCurrentSession()
        _state.value = PlayerState.CONNECTING
        lastEndpoint = Endpoint(host, port, options)
        autoReconnectEnabled = true
        this.useSoftwareDecoder = useSoftwareDecoder
        this.liveEdgeTargetMs = liveEdgeTargetMs
        _lastError.value = null
        _reconnectAttempt.value = 0
        if (!wifiLock.isHeld) runCatching { wifiLock.acquire() }

        // Rebuild ExoPlayer if the buffer ceiling has changed since the last
        // session — LoadControl is set at construction time and is immutable
        // afterwards.
        if (maxBufferMs != currentMaxBufferMs) {
            rebuildPlayer(maxBufferMs)
            currentMaxBufferMs = maxBufferMs
        }

        // (Re)create the sync controller if the user enabled SEI_SYNC.
        teardownSyncController()
        var createdSyncController: PlayerSyncController? = null
        if (seiSync != null) {
            val controller = PlayerSyncController(_player, seiSync)
            syncController = controller
            createdSyncController = controller
            startSyncControllerObservers(controller)
        }

        // LOW_LATENCY catch-up loop. Do not call seekTo() here: with a
        // ProgressiveMediaSource backed by an SRT socket, every seek forces a
        // loader reset and opens a brand-new SRT connection. That keeps video
        // close to live, but it also flushes codecs/audio continuously. We use
        // a small playback-speed nudge instead; SEI_SYNC owns speed itself.
        liveEdgeJob?.cancel()
        if (liveEdgeTargetMs > 0 && seiSync == null) {
            liveEdgeJob = scope.launch {
                var lastSpeed = 1.0f
                while (isActive) {
                    delay(LIVE_EDGE_CHECK_MS)
                    if (!_player.isPlaying) continue
                    val current = _player.currentPosition
                    val buffered = _player.bufferedPosition
                    val gap = buffered - current
                    val nextSpeed = when {
                        gap > liveEdgeTargetMs * 4L -> LOW_LATENCY_FAST_SPEED
                        gap > liveEdgeTargetMs * 2L -> LOW_LATENCY_CATCHUP_SPEED
                        gap <= (liveEdgeTargetMs * 3L) / 2L -> 1.0f
                        else -> lastSpeed
                    }
                    if (kotlin.math.abs(nextSpeed - lastSpeed) > 0.001f) {
                        _player.playbackParameters = PlaybackParameters(nextSpeed)
                        lastSpeed = nextSpeed
                        Log.i(
                            TAG,
                            "lowLatencySpeed: gap=${gap}ms target=${liveEdgeTargetMs}ms " +
                                    "speed=${"%.3f".format(nextSpeed)}"
                        )
                    }
                }
            }
        }

        if (createdSyncController != null) {
            startSessionAfterClockSync(host, createdSyncController)
        } else {
            startSession()
        }
    }

    fun disconnect() {
        autoReconnectEnabled = false
        cancelReconnect()
        clockSyncJob?.cancel(); clockSyncJob = null
        teardownCurrentSession()
        teardownSyncController()
        liveEdgeJob?.cancel(); liveEdgeJob = null
        mainHandler.post { _player.playbackParameters = PlaybackParameters(1.0f) }
        lastEndpoint = null
        _state.value = PlayerState.IDLE
        _stats.value = SrtStats()
        _mediaInfo.value = MediaInfo()
        _reconnectAttempt.value = 0
        if (wifiLock.isHeld) runCatching { wifiLock.release() }
    }

    fun onAppResumed() {
        if (recentlyInPip) return
        val ep = lastEndpoint ?: return
        if (!autoReconnectEnabled) return
        cancelReconnect()
        teardownCurrentSession()
        _state.value = PlayerState.CONNECTING
        _reconnectAttempt.value = 0
        startSession(ep)
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        mainHandler.post { _player.setVideoSurface(surface) }
    }

    fun release() {
        autoReconnectEnabled = false
        cancelReconnect()
        clockSyncJob?.cancel(); clockSyncJob = null
        teardownSyncController()
        liveEdgeJob?.cancel(); liveEdgeJob = null
        mainHandler.post { _player.playbackParameters = PlaybackParameters(1.0f) }
        statsJob?.cancel()
        scope.cancel()
        if (wifiLock.isHeld) runCatching { wifiLock.release() }
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        mainHandler.post { _player.release() }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun startSession(endpoint: Endpoint? = lastEndpoint) {
        val ep = endpoint ?: return
        val starting = _state.value == PlayerState.RECONNECTING
        if (!starting) _state.value = PlayerState.CONNECTING

        // The data source delivers SEI timestamps from ExoPlayer's Loader
        // thread; PlayerSyncController has to touch player.playbackParameters
        // which is main-thread-only. Bounce via mainHandler.
        val seiSink: ((Long) -> Unit)? = syncController?.let { c ->
            { ts -> mainHandler.post { c.onSeiTimestamp(ts) } }
        }
        val dsFactory = SrtDataSourceFactory(
            options = ep.options,
            onDataSourceCreated = { ds ->
                activeDataSource = ds
                attachStatsFlow(ds)
            },
            onSeiTimestamp = seiSink
        )
        val mediaSource = SrtMediaSourceFactory(dsFactory)
            .create(Uri.parse("srt://${ep.host}:${ep.port}"))

        mainHandler.post {
            _player.setMediaSource(mediaSource)
            _player.prepare()
            _player.playWhenReady = true
        }
    }

    private fun startSessionAfterClockSync(host: String, controller: PlayerSyncController) {
        clockSyncJob = scope.launch {
            val sample = estimateRelayClockWithRetry(host, controller, maxAttempts = 3)
            if (syncController !== controller || !autoReconnectEnabled) return@launch
            if (sample != null) {
                controller.updateClockOffset(sample.offsetMs)
            } else {
                Log.w(TAG, "clockSync: unavailable, falling back to raw SEI wall-clock lag")
            }
            startSession()
            if (sample == null) {
                retryRelayClockSyncInBackground(host, controller)
            }
        }
    }

    private suspend fun estimateRelayClockWithRetry(
        host: String,
        controller: PlayerSyncController,
        maxAttempts: Int
    ): RelayClockSync.Sample? {
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val sample = RelayClockSync.estimate(host)
            if (sample != null || syncController !== controller || !autoReconnectEnabled) {
                return sample
            }
            if (attempt < maxAttempts - 1) delay(CLOCK_SYNC_RETRY_MS)
        }
        return null
    }

    private fun retryRelayClockSyncInBackground(host: String, controller: PlayerSyncController) {
        clockSyncJob = scope.launch {
            while (isActive && syncController === controller && autoReconnectEnabled) {
                delay(CLOCK_SYNC_RETRY_MS)
                val sample = RelayClockSync.estimate(host)
                if (syncController !== controller || !autoReconnectEnabled) return@launch
                if (sample != null) {
                    controller.updateClockOffset(sample.offsetMs)
                    Log.i(TAG, "clockSync: recovered offset=${sample.offsetMs}ms")
                    return@launch
                }
            }
        }
    }

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
            _player.playbackParameters = PlaybackParameters(1.0f)
            _player.stop()
            _player.clearMediaItems()
        }
        _stats.value = SrtStats()
        syncController?.reset()
    }

    private fun teardownSyncController() {
        syncStaleJob?.cancel(); syncStaleJob = null
        syncMirrorJob?.cancel(); syncMirrorJob = null
        syncController?.reset()
        syncController = null
        _syncState.value = null
        _measuredLagMs.value = null
    }

    private fun startSyncControllerObservers(controller: PlayerSyncController) {
        // Mirror the controller's flows so the ViewModel/UI don't need to
        // worry about controller swap-outs across reconnects.
        syncMirrorJob = scope.launch {
            launch { controller.state.collect { _syncState.value = it } }
            launch { controller.measuredLagMs.collect { _measuredLagMs.value = it } }
        }
        // Ticks the staleness check so the state collapses to NO_SEI when
        // the relay stops emitting marks (e.g. SEI was turned off mid-stream).
        syncStaleJob = scope.launch {
            while (isActive) {
                delay(1000)
                controller.checkStaleness()
            }
        }
    }

    private fun attachStatsFlow(ds: SrtDataSource) {
        statsJob?.cancel()
        statsJob = scope.launch {
            ds.stats.collect { _stats.value = it }
        }
    }

    // ------------------------------------------------------------------
    // ExoPlayer (re)build
    // ------------------------------------------------------------------

    private fun buildPlayer(maxBufferMs: Int): ExoPlayer {
        val safeMaxBuf = maxBufferMs.coerceAtLeast(150)
        Log.i(TAG, "buildPlayer: maxBufferMs=$safeMaxBuf")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 100.coerceAtMost(safeMaxBuf),
                /* maxBufferMs = */ safeMaxBuf,
                /* bufferForPlaybackMs = */ 0,
                /* bufferForPlaybackAfterRebufferMs = */ 50.coerceAtMost(safeMaxBuf)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val factory = LowLatencyRenderersFactory(appContext) { useSoftwareDecoder }
        val player = ExoPlayer.Builder(appContext, factory)
            .setLoadControl(loadControl)
            .build()

        player.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        player.addListener(playerListener)
        pendingSurface?.let { player.setVideoSurface(it) }
        return player
    }

    private fun rebuildPlayer(newMaxBufferMs: Int) {
        // Synchronously swap the ExoPlayer instance — we're called from the
        // main thread before startSession posts setMediaSource, so there is
        // no observer on the soon-to-be-released player at this point.
        val old = _player
        _player = buildPlayer(newMaxBufferMs)
        mainHandler.post { old.release() }
    }

    private companion object {
        const val PIP_GUARD_MS = 2000L
        const val TAG = "KarPlayer"
        const val LOW_LATENCY_CATCHUP_SPEED = 1.03f
        const val LOW_LATENCY_FAST_SPEED = 1.06f
        const val CLOCK_SYNC_RETRY_MS = 1_000L
        /** How often the live-edge watchdog wakes up to check buffer growth.
         *  Short enough that a 1-frame overshoot at 30 fps gets caught the
         *  same second; long enough to keep CPU usage negligible. */
        const val LIVE_EDGE_CHECK_MS = 250L
    }
}
