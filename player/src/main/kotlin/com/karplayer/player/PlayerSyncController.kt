package com.karplayer.player

import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Drives ExoPlayer's playback speed so the displayed position converges to
 * a fixed wall-clock lag behind the relay's SEI timestamp.
 *
 * When a `KarSEI-TSYNC` mark is found in the incoming stream the data
 * source calls [onSeiTimestamp]. We measure
 * `lag = now() - sei_micros`, compare it against [SeiSyncConfig.targetLagMs],
 * and apply one of three speeds:
 *
 *   - within ±deadband of target → 1.0× (LOCKED)
 *   - we're behind (lag > target + deadband) → 1 + maxAdjust/100 (CATCHING_UP)
 *   - we're ahead (lag < target − deadband) → 1 − maxAdjust/100 (SLOWING_DOWN)
 *
 * If no SEI has been seen for [STALE_MS], state collapses to [State.NO_SEI]
 * and speed is restored to 1.0×. Call [checkStaleness] periodically (e.g.
 * once per second) for that side-effect to fire while the stream is silent.
 */
class PlayerSyncController(
    private val player: ExoPlayer,
    config: SeiSyncConfig
) {

    data class SeiSyncConfig(
        val targetLagMs: Long,
        val deadbandMs: Long,
        val maxSpeedAdjustPct: Int,
        val clockOffsetMs: Long = 0L
    )

    enum class State { NO_SEI, LOCKED, CATCHING_UP, SLOWING_DOWN }

    @Volatile var config: SeiSyncConfig = config
        private set

    private val _state = MutableStateFlow(State.NO_SEI)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _measuredLagMs = MutableStateFlow<Long?>(null)
    val measuredLagMs: StateFlow<Long?> = _measuredLagMs.asStateFlow()

    @Volatile private var lastSeiAtMonoNs: Long = 0L
    private var seiDebugCount: Long = 0L
    private var lastDebugLogAtMonoNs: Long = 0L
    private var lastDebugState: State? = null

    /**
     * Called by the data-source layer for every SEI timestamp parsed out of
     * the stream. [seiMicros] is UTC microseconds from the relay's wall
     * clock.
     */
    fun onSeiTimestamp(seiMicros: Long) {
        val nowMicros = System.currentTimeMillis() * 1000L
        val rawLagMs = (nowMicros - seiMicros) / 1000L
        val edgeLagMs = rawLagMs - config.clockOffsetMs
        val playerGapMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        val lagMs = edgeLagMs + playerGapMs
        _measuredLagMs.value = lagMs
        lastSeiAtMonoNs = System.nanoTime()

        val decision = decideSpeed(lagMs, config)
        applySpeed(decision.speed)
        _state.value = decision.state
        logSeiDebugIfNeeded(seiMicros, nowMicros, rawLagMs, edgeLagMs, playerGapMs, lagMs, decision)
    }

    /** Detects SEI silence — if more than [STALE_MS] passed without a fresh
     *  mark we drop to NO_SEI and restore neutral speed. Cheap to call from
     *  a 1-second timer. */
    fun checkStaleness() {
        if (lastSeiAtMonoNs == 0L) return
        if ((System.nanoTime() - lastSeiAtMonoNs) > STALE_NS) {
            if (_state.value != State.NO_SEI) {
                applySpeed(1.0f)
                _state.value = State.NO_SEI
            }
        }
    }

    /** Drop accumulated state. Called from PlayerManager on disconnect. */
    fun reset() {
        lastSeiAtMonoNs = 0L
        seiDebugCount = 0L
        lastDebugLogAtMonoNs = 0L
        lastDebugState = null
        _measuredLagMs.value = null
        if (_state.value != State.NO_SEI) {
            _state.value = State.NO_SEI
        }
        applySpeed(1.0f)
    }

    fun updateConfig(newConfig: SeiSyncConfig) {
        config = newConfig
    }

    fun updateClockOffset(clockOffsetMs: Long) {
        config = config.copy(clockOffsetMs = clockOffsetMs)
    }

    private fun applySpeed(speed: Float) {
        val current = player.playbackParameters.speed
        if (abs(current - speed) > 0.001f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    private fun logSeiDebugIfNeeded(
        seiMicros: Long,
        nowMicros: Long,
        rawLagMs: Long,
        edgeLagMs: Long,
        playerGapMs: Long,
        lagMs: Long,
        decision: SpeedDecision
    ) {
        seiDebugCount += 1
        val monoNs = System.nanoTime()
        val stateChanged = lastDebugState != decision.state
        val elapsedSinceLogMs = (monoNs - lastDebugLogAtMonoNs) / 1_000_000L
        val shouldLog = seiDebugCount <= INITIAL_DEBUG_LOGS ||
                stateChanged ||
                elapsedSinceLogMs >= DEBUG_LOG_INTERVAL_MS
        if (!shouldLog) return

        lastDebugLogAtMonoNs = monoNs
        lastDebugState = decision.state

        val targetMs = config.targetLagMs
        val deltaMs = lagMs - targetMs
        val clockOffsetMs = config.clockOffsetMs
        val relayMs = seiMicros / 1000L
        val phoneMs = nowMicros / 1000L
        Log.i(
            TAG,
            "seiSync: count=$seiDebugCount state=${decision.state} " +
                    "lag=${lagMs}ms edgeLag=${edgeLagMs}ms rawLag=${rawLagMs}ms " +
                    "clockOffset=${clockOffsetMs}ms " +
                    "target=${targetMs}ms delta=${deltaMs}ms " +
                    "phoneMs=$phoneMs relayMs=$relayMs speed=${"%.3f".format(decision.speed)} " +
                    "pos=${player.currentPosition}ms buffered=${player.bufferedPosition}ms " +
                    "gap=${playerGapMs}ms playState=${player.playbackState} playing=${player.isPlaying}"
        )
    }

    companion object {
        private const val TAG = "KarPlayer"

        /** How long to wait for a fresh SEI before declaring the stream
         *  un-timecoded (relay sends one per I-frame; GOP of ≥ 4 s would be
         *  unusual). */
        const val STALE_MS: Long = 5_000L
        private const val STALE_NS: Long = STALE_MS * 1_000_000L
        private const val INITIAL_DEBUG_LOGS: Int = 10
        private const val DEBUG_LOG_INTERVAL_MS: Long = 1_000L

        /** Decision wrapper for testability — pure function from
         *  (lag, config) → (speed, state). */
        data class SpeedDecision(val speed: Float, val state: State)

        internal fun decideSpeed(lagMs: Long, cfg: SeiSyncConfig): SpeedDecision {
            val delta = lagMs - cfg.targetLagMs
            val adjust = cfg.maxSpeedAdjustPct.coerceIn(0, 50) / 100f
            val speed = when {
                delta > cfg.deadbandMs -> 1.0f + adjust
                delta < -cfg.deadbandMs -> 1.0f - adjust
                else -> 1.0f
            }
            val state = when {
                speed > 1.0f -> State.CATCHING_UP
                speed < 1.0f -> State.SLOWING_DOWN
                else -> State.LOCKED
            }
            return SpeedDecision(speed, state)
        }
    }
}
