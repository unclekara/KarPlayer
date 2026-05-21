package com.karplayer

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.karplayer.ui.KarPlayerNavRoot

class MainActivity : ComponentActivity() {

    private val app: KarPlayerApp get() = application as KarPlayerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    KarPlayerNavRoot(playerManager = app.playerManager)
                }
            }
        }
    }

    /**
     * Picture-in-Picture entry. Triggered when the user navigates away from
     * the app (Home / Recents) while a stream is playing. Skipped when the
     * player is idle so we don't shrink the connect form into a tiny window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val pm = app.playerManager
        if (!pm.player.isPlaying) return

        val media = pm.mediaInfo.value
        val width = media.videoWidth.takeIf { it > 0 } ?: 16
        val height = media.videoHeight.takeIf { it > 0 } ?: 9
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.playerManager.setInPipMode(isInPictureInPictureMode)
    }
}
