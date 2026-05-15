package com.karplayer

import android.app.Application
import com.karplayer.player.PlayerManager

class KarPlayerApp : Application() {

    lateinit var playerManager: PlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        playerManager = PlayerManager(applicationContext)
    }

    override fun onTerminate() {
        playerManager.release()
        super.onTerminate()
    }
}
