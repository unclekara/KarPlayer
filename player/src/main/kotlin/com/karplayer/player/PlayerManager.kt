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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.karplayer.srt.SrtConnectException
import com.karplayer.srt.SrtDataSource
import com.karplayer.srt.SrtOptions
import com.karplayer.srt.SrtStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // Emitted when the player gives up (reconnects exhausted) and the UI
    // should return to the connection menu. The payload is the reason
    // text to surface there.
    private val _exitToMenu = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val exitToMenu: SharedFlow<String?> = _exitToMenu.asSharedFlow()

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val _selectedAudioTrackId = MutableStateFlow<String?>(null)
    val selectedAudioTrackId: StateFlow<String?> = _selectedAudioTrackId.asStateFlow()

    /** Most recent Tracks snapshot — needed to resolve the TrackGroup
     *  reference when [selectAudio] is called. */
    @Volatile private var lastTracks: Tracks? = null

    /** User's preferred audio language (ISO-639 code) carried from
     *  [ConnectionConfig]. Applied via [TrackSelectionParameters] on
     *  every (re)build so a buffer-ceiling change doesn't drop it. */
    @Volatile private var preferredAudioLanguage: String? = null

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

    /** Kiosk / digital-signage session. Reconnect is unbounded: we never
     *  give up and never emit [exitToMenu], so the UI can stay on a black
     *  screen until the signal comes back. Set per connect() call. */
    @Volatile private var kioskMode: Boolean = false
    private var relayHttpPort: Int = 8484  // for the disconnect-reason side-channel
    @Volatile private var errorEpoch: Int = 0  // bumped on every onPlayerError

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
            errorEpoch++  // lets an in-flight reconnect attempt detect this failure
            // Set an initial message; a connect-time SRT rejection gives us
            // a precise reason synchronously. Mid-session kicks surface as
            // generic errors — scheduleReconnect refines the text from the
            // relay's side-channel.
            val rejectEx = findSrtConnectException(error)
            _lastError.value = when {
                rejectEx?.isWrongStreamId == true ->
                    "Wrong stream ID — not allowed for this source."
                rejectEx?.isViewerLimit == true ->
                    "Relay free plan: max 3 viewers per source."
                else -> error.message ?: error.errorCodeName
            }

            // Uniform policy: any error (network drop, kick, rejection) gets
            // up to MAX_RECONNECT_ATTEMPTS retries, then we bail to the menu.
            if (autoReconnectEnabled && lastEndpoint != null) {
                scheduleReconnect()
            } else {
                _state.value = PlayerState.ERROR
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            lastTracks = tracks

            var video: Format? = null
            var selectedAudio: Format? = null
            var selectedAudioId: String? = null
            val audioList = mutableListOf<AudioTrack>()

            for ((gi, group) in tracks.groups.withIndex()) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val mime = f.sampleMimeType
                    if (MimeTypes.isVideo(mime) && group.isSelected && group.isTrackSelected(i) && video == null) {
                        video = f
                    }
                    if (MimeTypes.isAudio(mime)) {
                        val isSel = group.isSelected && group.isTrackSelected(i)
                        val tid = "$gi-$i"
                        audioList += AudioTrack(
                            id = tid,
                            language = f.language,
                            label = f.label,
                            codec = mime?.removePrefix("audio/")?.uppercase(),
                            channels = f.channelCount,
                            sampleRate = f.sampleRate,
                            isSelected = isSel,
                        )
                        if (isSel) {
                            selectedAudio = f
                            selectedAudioId = tid
                        }
                    }
                }
            }
            _audioTracks.value = audioList
            _selectedAudioTrackId.value = selectedAudioId

            // Debug: dump what the demuxer surfaced. Helps diagnose
            // "I sent 4 audio tracks and only one plays" — most often
            // the encoder (e.g., vMix in multi-channel mode) muxed all
            // languages into a single multi-channel PID, so here we
            // see audio=1 with channels=8 rather than audio=4.
            val audioSummary = audioList.joinToString(", ") { t ->
                "${t.id}:${t.codec ?: "?"}/${t.language ?: "—"}/${t.channels}ch" +
                        if (t.isSelected) "[SEL]" else ""
            }
            Log.i(
                TAG,
                "tracks: video=${video?.sampleMimeType ?: "—"}" +
                        "(${video?.width ?: 0}x${video?.height ?: 0}@${video?.frameRate ?: 0f}) " +
                        "audio.count=${audioList.size} [$audioSummary]"
            )

            _mediaInfo.value = _mediaInfo.value.copy(
                videoCodec = video?.sampleMimeType?.removePrefix("video/")?.uppercase(),
                videoWidth = video?.width ?: 0,
                videoHeight = video?.height ?: 0,
                videoFrameRate = video?.frameRate ?: 0f,
                audioCodec = selectedAudio?.sampleMimeType?.removePrefix("audio/")?.uppercase(),
                audioSampleRate = selectedAudio?.sampleRate ?: 0,
                audioChannels = selectedAudio?.channelCount ?: 0
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
        liveEdgeTargetMs: Int = 0,
        relayHttpPort: Int = 8484,
        preferredAudioLanguage: String? = null,
        kioskMode: Boolean = false,
    ) {
        Log.i(
            TAG,
            "connect: $host:$port maxBufferMs=$maxBufferMs kiosk=$kioskMode " +
                    "seiSync=${seiSync != null} liveEdgeTargetMs=$liveEdgeTargetMs " +
                    "relayHttpPort=$relayHttpPort " +
                    "srtLatency=${options.latency} sw=$useSoftwareDecoder"
        )
        cancelReconnect()
        clockSyncJob?.cancel(); clockSyncJob = null
        teardownCurrentSession()
        _state.value = PlayerState.CONNECTING
        lastEndpoint = Endpoint(host, port, options)
        this.relayHttpPort = relayHttpPort
        this.kioskMode = kioskMode
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

        // Apply the persisted audio-language preference on every connect
        // so a stream rebuild (or a fresh session after disconnect) opens
        // on the user's last choice. Track-list state is per-session,
        // any prior in-session override resets to a clean default.
        _audioTracks.value = emptyList()
        _selectedAudioTrackId.value = null
        setPreferredAudioLanguage(preferredAudioLanguage)

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
            startSessionAfterClockSync(host, relayHttpPort, createdSyncController)
        } else {
            startSession()
        }
    }

    /** Clears the last error banner (the menu auto-dismisses it after a
     *  few seconds so the player doesn't look permanently "limited"). */
    fun clearError() {
        _lastError.value = null
    }

    /**
     * Picks the audio track identified by [trackId] (one of the ids in
     * [audioTracks]). Returns the picked track's language (ISO-639), or
     * null if the track has no language or the id is unknown. Callers
     * persist the returned language so subsequent sessions open on the
     * same track.
     *
     * The override applies immediately; ExoPlayer's selector seamlessly
     * swaps the audio renderer's input without restarting the SRT
     * connection.
     */
    fun selectAudio(trackId: String): String? {
        val tracks = lastTracks ?: return null
        val (gi, ti) = parseTrackId(trackId) ?: return null
        if (gi !in tracks.groups.indices) return null
        val group = tracks.groups[gi]
        if (ti !in 0 until group.length) return null
        val format = group.getTrackFormat(ti)
        mainHandler.post {
            val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(ti))
            _player.trackSelectionParameters = _player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .build()
        }
        // Mirror the choice into the persistent default so reconnects
        // and player rebuilds re-pick the same language.
        format.language?.let { lang ->
            preferredAudioLanguage = lang
            mainHandler.post {
                _player.trackSelectionParameters = _player.trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage(lang)
                    .build()
            }
        }
        return format.language
    }

    /**
     * Applies a preferred audio language on the active ExoPlayer (no-op
     * if [lang] is null/blank). Called by [connect] from the persisted
     * [ConnectionConfig] value; also kept around so a rebuild of the
     * ExoPlayer doesn't lose the preference.
     */
    fun setPreferredAudioLanguage(lang: String?) {
        preferredAudioLanguage = lang
        mainHandler.post {
            _player.trackSelectionParameters = _player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage(lang)
                .build()
        }
    }

    private fun parseTrackId(id: String): Pair<Int, Int>? {
        val dash = id.indexOf('-')
        if (dash <= 0 || dash == id.length - 1) return null
        val gi = id.substring(0, dash).toIntOrNull() ?: return null
        val ti = id.substring(dash + 1).toIntOrNull() ?: return null
        return gi to ti
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
        _audioTracks.value = emptyList()
        _selectedAudioTrackId.value = null
        lastTracks = null
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

    private fun startSessionAfterClockSync(
        host: String,
        webPort: Int,
        controller: PlayerSyncController
    ) {
        clockSyncJob = scope.launch {
            val sample = estimateRelayClockWithRetry(host, webPort, controller, maxAttempts = 3)
            if (syncController !== controller || !autoReconnectEnabled) return@launch
            if (sample != null) {
                controller.updateClockOffset(sample.offsetMs)
            } else {
                Log.w(TAG, "clockSync: unavailable, falling back to raw SEI wall-clock lag")
            }
            startSession()
            if (sample == null) {
                retryRelayClockSyncInBackground(host, webPort, controller)
            }
        }
    }

    private suspend fun estimateRelayClockWithRetry(
        host: String,
        webPort: Int,
        controller: PlayerSyncController,
        maxAttempts: Int
    ): RelayClockSync.Sample? {
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val sample = RelayClockSync.estimate(host, webPort = webPort)
            if (sample != null || syncController !== controller || !autoReconnectEnabled) {
                return sample
            }
            if (attempt < maxAttempts - 1) delay(CLOCK_SYNC_RETRY_MS)
        }
        return null
    }

    private fun retryRelayClockSyncInBackground(
        host: String,
        webPort: Int,
        controller: PlayerSyncController
    ) {
        clockSyncJob = scope.launch {
            while (isActive && syncController === controller && autoReconnectEnabled) {
                delay(CLOCK_SYNC_RETRY_MS)
                val sample = RelayClockSync.estimate(host, webPort = webPort)
                if (syncController !== controller || !autoReconnectEnabled) return@launch
                if (sample != null) {
                    controller.updateClockOffset(sample.offsetMs)
                    Log.i(TAG, "clockSync: recovered offset=${sample.offsetMs}ms")
                    return@launch
                }
            }
        }
    }

    /** Walks the PlaybackException cause chain for a typed SRT
     *  handshake rejection. */
    private fun findSrtConnectException(t: Throwable?): SrtConnectException? {
        var cur: Throwable? = t
        var hops = 0
        while (cur != null && hops < 12) {
            if (cur is SrtConnectException) return cur
            cur = cur.cause
            hops++
        }
        return null
    }

    /** Asks the relay why the session dropped, via the HTTP side-channel.
     *  Returns the reason string ("kicked" / "viewer_limit" /
     *  "wrong_stream_id") or null if the relay has no fresh event for
     *  this streamid (i.e. a genuine network drop). Best-effort. */
    private suspend fun fetchDisconnectReason(): String? {
        val ep = lastEndpoint ?: return null
        val streamId = ep.options.streamId
        if (streamId.isEmpty()) return null
        return RelayClockSync.disconnectReason(ep.host, relayHttpPort, streamId)
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val ep = lastEndpoint ?: return
        _state.value = PlayerState.RECONNECTING

        // Refine the reason text from the relay's side-channel (kicks and
        // limits surface here even when the SRT error was generic). Purely
        // cosmetic — does not affect the retry flow.
        scope.launch {
            when (fetchDisconnectReason()) {
                "kicked"          -> _lastError.value = "Disconnected by the relay operator."
                "viewer_limit"    -> _lastError.value = "Relay free plan: max 3 viewers per source."
                "wrong_stream_id" -> _lastError.value = "Wrong stream ID — not allowed for this source."
            }
        }

        reconnectJob = scope.launch {
            while (isActive && autoReconnectEnabled && lastEndpoint != null) {
                val attempt = _reconnectAttempt.value + 1
                // Kiosk sessions never give up: an unattended screen has
                // nobody to read a menu, so we keep retrying at the capped
                // backoff and let the UI hold a black frame until the
                // signal returns.
                if (!kioskMode && attempt > MAX_RECONNECT_ATTEMPTS) {
                    // Out of attempts — give up and return to the menu with
                    // the last reason shown there.
                    autoReconnectEnabled = false
                    _state.value = PlayerState.ERROR
                    _exitToMenu.tryEmit(_lastError.value)
                    break
                }
                _reconnectAttempt.value = attempt
                delay(backoffDelay(attempt))
                if (!autoReconnectEnabled) break

                val epochBefore = errorEpoch
                teardownCurrentSession()
                startSession(ep)

                // Wait for a *definitive* outcome. prepare() flips ExoPlayer
                // to BUFFERING immediately, so BUFFERING is NOT success — we
                // only count PLAYING. A new onPlayerError (errorEpoch bump)
                // or the connect timeout means this attempt failed; loop on
                // to the next attempt (which increments the counter).
                val deadline = System.currentTimeMillis() + RECONNECT_OUTCOME_TIMEOUT_MS
                var success = false
                while (isActive && autoReconnectEnabled &&
                    System.currentTimeMillis() < deadline
                ) {
                    if (_state.value == PlayerState.PLAYING) { success = true; break }
                    if (errorEpoch != epochBefore) break  // attempt failed fast
                    delay(150)
                }
                if (success) {
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
        /** How many reconnect attempts before giving up and returning the
         *  user to the connection menu. */
        const val MAX_RECONNECT_ATTEMPTS = 3
        /** Per-attempt window to reach PLAYING before the attempt counts as
         *  failed. Instant rejections short-circuit via errorEpoch, so this
         *  only bounds the "stuck buffering" case. */
        const val RECONNECT_OUTCOME_TIMEOUT_MS = 6000L
    }
}
