package com.karplayer.player

import com.karplayer.player.PlayerSyncController.Companion.decideSpeed
import com.karplayer.player.PlayerSyncController.SeiSyncConfig
import com.karplayer.player.PlayerSyncController.State
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSyncControllerTest {

    private val cfg = SeiSyncConfig(
        targetLagMs = 250L,
        deadbandMs = 50L,
        maxSpeedAdjustPct = 5
    )

    @Test
    fun within_deadband_keeps_neutral_speed() {
        val (speed, state) = decideSpeed(240L, cfg)
        assertEquals(1.0f, speed, 0.0001f)
        assertEquals(State.LOCKED, state)
    }

    @Test
    fun above_deadband_speeds_up() {
        val (speed, state) = decideSpeed(350L, cfg)
        assertEquals(1.05f, speed, 0.0001f)
        assertEquals(State.CATCHING_UP, state)
    }

    @Test
    fun below_deadband_slows_down() {
        val (speed, state) = decideSpeed(100L, cfg)
        assertEquals(0.95f, speed, 0.0001f)
        assertEquals(State.SLOWING_DOWN, state)
    }

    @Test
    fun exactly_on_target_locks() {
        val (speed, state) = decideSpeed(250L, cfg)
        assertEquals(1.0f, speed, 0.0001f)
        assertEquals(State.LOCKED, state)
    }

    @Test
    fun maxAdjust_zero_always_locked() {
        val flat = cfg.copy(maxSpeedAdjustPct = 0)
        assertEquals(1.0f, decideSpeed(0L, flat).speed, 0.0001f)
        assertEquals(1.0f, decideSpeed(10_000L, flat).speed, 0.0001f)
        // Speed is 1.0 in both cases so state collapses to LOCKED.
        assertEquals(State.LOCKED, decideSpeed(10_000L, flat).state)
    }
}
