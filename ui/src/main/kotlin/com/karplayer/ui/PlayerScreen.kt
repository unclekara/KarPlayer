package com.karplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import com.karplayer.player.MediaInfo
import com.karplayer.player.PlayerState
import com.karplayer.srt.SrtStats
import kotlinx.coroutines.delay

private enum class HudKind { VOLUME, BRIGHTNESS }
private data class HudState(val kind: HudKind, val value: Float)

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onDisconnect: () -> Unit
) {
    val state by viewModel.playerState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val media by viewModel.mediaInfo.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var overlayVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(true) }
    var hud by remember { mutableStateOf<HudState?>(null) }

    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val audioManager = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volumeLevel by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
    }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }

    // Immersive system bars while this screen lives. Restore on dispose so
    // ConnectionScreen still gets status/nav bars back.
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { c ->
                AspectRatioFrameLayout(c).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val sv = SurfaceView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                viewModel.playerManager.setSurface(h.surface)
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                viewModel.playerManager.setSurface(null)
                            }
                        })
                    }
                    addView(sv)
                }
            },
            update = { layout ->
                val ratio = media.aspectRatio
                if (ratio > 0f) layout.setAspectRatio(ratio)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture surface — tap toggles overlay; vertical drag adjusts
        // brightness (left half) or volume (right half). Long-press unlocks.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (!locked) overlayVisible = !overlayVisible },
                        onLongPress = { if (locked) locked = false }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val w = size.width.coerceAtLeast(1)
                        val isLeft = change.position.x < w / 2f
                        val delta = -dragAmount / size.height.coerceAtLeast(1).toFloat()
                        if (isLeft) {
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0.05f, 1f)
                            activity?.window?.attributes = activity?.window?.attributes?.apply {
                                screenBrightness = brightnessLevel
                            }
                            hud = HudState(HudKind.BRIGHTNESS, brightnessLevel)
                        } else {
                            volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (volumeLevel * maxVolume).toInt(),
                                0
                            )
                            hud = HudState(HudKind.VOLUME, volumeLevel)
                        }
                    }
                }
        )

        DisposableEffect(Unit) {
            onDispose { viewModel.playerManager.setSurface(null) }
        }

        if (state == PlayerState.BUFFERING ||
            state == PlayerState.CONNECTING ||
            state == PlayerState.RECONNECTING
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                if (state == PlayerState.RECONNECTING) {
                    Text(
                        "Reconnecting… attempt $reconnectAttempt",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    lastError?.let {
                        Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }

        if (state == PlayerState.ERROR) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = lastError ?: "Playback error", color = Color.Red)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDisconnect) { Text("Back") }
            }
        }

        hud?.let { h ->
            HudBar(
                kind = h.kind,
                value = h.value,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Overlays — completely hidden while locked.
        if (!locked && overlayVisible) {
            StatsOverlay(
                stats = stats,
                media = media,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            BottomBar(
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onLock = { locked = true },
                onDisconnect = onDisconnect,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun BottomBar(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onLock: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            IconButton(onClick = onLock) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = Color.White)
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "Exit fullscreen" else "Enter fullscreen",
                    tint = Color.White
                )
            }
        }
        Button(onClick = onDisconnect) { Text("Disconnect") }
    }
}

@Composable
private fun HudBar(kind: HudKind, value: Float, modifier: Modifier = Modifier) {
    val (icon, label) = when (kind) {
        HudKind.VOLUME -> Icons.Filled.VolumeUp to "Volume"
        HudKind.BRIGHTNESS -> Icons.Filled.BrightnessHigh to "Brightness"
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier.width(160.dp).height(6.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f)
        )
        Text("${(value * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun StatsOverlay(
    stats: SrtStats,
    media: MediaInfo,
    modifier: Modifier = Modifier
) {
    val lossColor = when {
        stats.packetLossPct >= 1.0 -> Color(0xFFFF6B6B)
        stats.packetLossPct >= 0.1 -> Color(0xFFFFD166)
        else -> Color(0xFF9BE39B)
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Metric("RTT", "%.0f".format(stats.rttMs), "ms")
            Metric("Bitrate", "%.1f".format(stats.bitrateKbps / 1000.0), "Mbps")
            Metric("Loss", "%.2f".format(stats.packetLossPct), "%", lossColor)
            Metric("Jitter", "%.0f".format(stats.jitterMs), "ms")
        }

        val videoLine = buildString {
            append(media.videoCodec ?: "—")
            if (media.videoWidth > 0) append("  ").append(media.videoWidth).append("×").append(media.videoHeight)
            if (media.videoFrameRate > 0f) append("  ").append("%.2f".format(media.videoFrameRate)).append(" fps")
        }
        OverlayRow("Video", videoLine)

        val audioLine = buildString {
            append(media.audioCodec ?: "—")
            if (media.audioSampleRate > 0) append("  ").append(media.audioSampleRate).append(" Hz")
            if (media.audioChannels > 0) append("  ").append(media.audioChannels).append(" ch")
        }
        OverlayRow("Audio", audioLine)

        OverlayRow("Retx", stats.retransmittedPackets.toString())
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, valueColor: Color = Color.White) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontWeight = FontWeight.SemiBold)
            Text(" $unit", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun OverlayRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.width(56.dp)
        )
        Text(text = value, color = Color.White, fontSize = 12.sp)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
