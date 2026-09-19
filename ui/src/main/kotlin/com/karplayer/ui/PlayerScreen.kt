package com.karplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.karplayer.player.AudioTrack
import com.karplayer.player.MediaInfo
import com.karplayer.player.PlayerState
import com.karplayer.player.PlayerSyncController
import com.karplayer.srt.SrtStats
import kotlinx.coroutines.delay

private enum class HudKind { VOLUME, BRIGHTNESS }
private data class HudState(val kind: HudKind, val value: Float)

/** Kiosk mode shows no chrome at all, so a single stray BACK on a remote
 *  must not drop an unattended screen out of playback. Two presses inside
 *  this window leave the player. */
private const val KIOSK_EXIT_CONFIRM_MS = 2500L

/** Grace period before kiosk mode blanks the video layer to black. Long
 *  enough that an ordinary sub-second rebuffer does not flash black,
 *  short enough that a dead signal blanks promptly. */
private const val KIOSK_BLANK_DELAY_MS = 1000L

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
    val isInPip by viewModel.isInPip.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val syncLagMs by viewModel.measuredLagMs.collectAsState()
    val audioTracks by viewModel.audioTracks.collectAsState()
    val selectedAudioTrackId by viewModel.selectedAudioTrackId.collectAsState()
    // Kiosk mode: video on black, nothing else. Every overlay below is
    // gated on this, and PlayerManager keeps reconnecting forever so we
    // never get an exitToMenu for an unattended screen.
    val connectionConfig by viewModel.connectionConfig.collectAsState()
    val kiosk = connectionConfig.kioskMode
    var audioPickerVisible by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The player gave up reconnecting → return to the connection menu.
    // The reason text remains in lastError and is shown there.
    LaunchedEffect(Unit) {
        viewModel.exitToMenu.collect { onDisconnect() }
    }

    var overlayVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(true) }
    var hud by remember { mutableStateOf<HudState?>(null) }

    val isTv = isTvDevice()
    // Take focus on TV so the D-pad has something to act on. Also lets us
    // intercept OK / Enter to toggle the stats overlay.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) { if (isTv) rootFocus.requestFocus() }
    // BACK on a remote (or phone) should leave the player cleanly. If the
    // overlay is locked we eat the press once and unlock instead, mirroring
    // the long-press-to-unlock gesture on touch. In kiosk mode a single
    // press is not enough — see KIOSK_EXIT_CONFIRM_MS.
    var kioskExitArmedAtMs by remember { mutableLongStateOf(0L) }
    var kioskExitHintVisible by remember { mutableStateOf(false) }
    BackHandler {
        when {
            locked -> locked = false
            kiosk -> {
                val now = System.currentTimeMillis()
                if (now - kioskExitArmedAtMs <= KIOSK_EXIT_CONFIRM_MS) {
                    onDisconnect()
                } else {
                    kioskExitArmedAtMs = now
                    kioskExitHintVisible = true
                }
            }
            else -> onDisconnect()
        }
    }
    LaunchedEffect(kioskExitArmedAtMs) {
        if (kioskExitArmedAtMs != 0L) {
            delay(KIOSK_EXIT_CONFIRM_MS)
            kioskExitHintVisible = false
        }
    }

    // A SurfaceView keeps its last decoded frame on screen after the
    // player stops, so a lost signal would otherwise freeze on a stale
    // image — the opposite of what an unattended screen should show.
    // Kiosk mode covers the video layer with opaque black whenever we are
    // not actually playing.
    var kioskBlank by remember { mutableStateOf(false) }
    LaunchedEffect(kiosk, state) {
        if (!kiosk) {
            kioskBlank = false
        } else if (state == PlayerState.PLAYING) {
            kioskBlank = false
        } else {
            delay(KIOSK_BLANK_DELAY_MS)
            kioskBlank = true
        }
    }

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

        // Gesture surface. On phones: tap toggles overlay; vertical drag on
        // left half adjusts brightness, right half adjusts volume; long-press
        // unlocks. On TV: D-pad CENTER toggles overlay (brightness/volume on
        // a TV are owned by the system / remote, swipes are meaningless
        // without touch). Modifiers are no-ops in their off-platform case but
        // we skip the touch-only ones on TV to keep the surface lean.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!locked && !kiosk) overlayVisible = !overlayVisible
                            true
                        }
                        else -> false
                    }
                }
                .then(
                    if (kiosk) Modifier else Modifier.pointerInput(locked) {
                        detectTapGestures(
                            onTap = { if (!locked) overlayVisible = !overlayVisible },
                            onLongPress = { if (locked) locked = false }
                        )
                    }
                )
                .then(
                    if (isTv || kiosk) Modifier else Modifier.pointerInput(locked) {
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
        )

        DisposableEffect(Unit) {
            onDispose { viewModel.playerManager.setSurface(null) }
        }

        if (kioskBlank) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Kiosk mode holds a plain black frame instead: no spinner, no
        // attempt counter, nothing that reads as a broken screen to a
        // passer-by while the sender is off air.
        if (!kiosk && (state == PlayerState.BUFFERING ||
            state == PlayerState.CONNECTING ||
            state == PlayerState.RECONNECTING)
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

        if (!kiosk && state == PlayerState.ERROR) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = lastError ?: "Playback error", color = Color.Red)
                Spacer(Modifier.height(8.dp))
                FocusableButton(onClick = onDisconnect) { Text("Back") }
            }
        }

        if (!kiosk) hud?.let { h ->
            HudBar(
                kind = h.kind,
                value = h.value,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Overlays — completely hidden while locked or while in
        // picture-in-picture (the window is too small for chrome anyway,
        // and Android's own PiP gesture surface fights ours otherwise).
        if (!kiosk && !locked && overlayVisible && !isInPip) {
            StatsOverlay(
                stats = stats,
                media = media,
                audioTrackCount = audioTracks.size,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Live SEI-sync indicator (top-right). Visible only when the
            // user picked SyncMode.SEI_SYNC on connect — PlayerManager
            // leaves syncState null otherwise.
            syncState?.let { s ->
                SyncIndicator(
                    state = s,
                    lagMs = syncLagMs,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                )
            }

            BottomBar(
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onLock = { locked = true },
                onDisconnect = onDisconnect,
                onAudio = if (audioTracks.size >= 2) ({ audioPickerVisible = true }) else null,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // The single piece of kiosk chrome, and only after the user has
        // already pressed BACK once — without it there is no way to tell
        // that a second press is expected.
        if (kiosk && kioskExitHintVisible) {
            Text(
                text = "Press BACK again to exit",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            )
        }

        if (audioPickerVisible) {
            AudioTrackPicker(
                tracks = audioTracks,
                selectedId = selectedAudioTrackId,
                onPick = { id ->
                    viewModel.selectAudio(id, ctx)
                    audioPickerVisible = false
                },
                onDismiss = { audioPickerVisible = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

    }
}

/**
 * Renders [icon] with a 1dp dark drop shadow behind a white foreground
 * so the glyph stays readable on any video frame (black studio, white
 * sky, mid-tone newsroom). Pattern matches YouTube / Netflix / Plex
 * overlay chrome — minimal visual noise, maximum contrast.
 */
@Composable
private fun ShadowedIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
) {
    Box {
        // Shadow pass: offset 1dp down-right, semi-transparent black.
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.offset(x = 1.dp, y = 1.dp)
        )
        // Foreground pass: pure white, full opacity.
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White
        )
    }
}

@Composable
private fun BottomBar(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onLock: () -> Unit,
    onDisconnect: () -> Unit,
    onAudio: (() -> Unit)?,
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
            FocusableIconButton(onClick = onLock) {
                ShadowedIcon(icon = Icons.Filled.Lock, contentDescription = "Lock")
            }
            FocusableIconButton(onClick = onToggleFullscreen) {
                ShadowedIcon(
                    icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "Exit fullscreen" else "Enter fullscreen"
                )
            }
            if (onAudio != null) {
                FocusableIconButton(onClick = onAudio) {
                    ShadowedIcon(icon = Icons.Filled.MusicNote, contentDescription = "Audio track")
                }
            }
        }
        FocusableButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

/**
 * Bottom-anchored audio-track picker. Each row shows label / language /
 * codec. A pure-Compose implementation (no Material3 ModalBottomSheet)
 * to keep behaviour identical on TV — ModalBottomSheet's focus model
 * doesn't always play well with leanback D-pad navigation.
 */
@Composable
private fun AudioTrackPicker(
    tracks: List<AudioTrack>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xCC101010), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    detectTapGestures { /* swallow taps inside the sheet */ }
                }
        ) {
            Text(
                text = "Audio",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            tracks.forEach { tr ->
                AudioTrackRow(
                    track = tr,
                    isSelected = tr.id == selectedId,
                    onClick = { onPick(tr.id) }
                )
            }
            Spacer(Modifier.height(8.dp))
            FocusableButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun AudioTrackRow(track: AudioTrack, isSelected: Boolean, onClick: () -> Unit) {
    val primary = track.label?.takeIf { it.isNotBlank() }
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: track.id
    val meta = buildString {
        track.language?.takeIf { it.isNotBlank() && it != primary }?.let { append(it.uppercase()) }
        track.codec?.let { if (isNotEmpty()) append(" · "); append(it) }
        if (track.channels > 0) { if (isNotEmpty()) append(" · "); append("${track.channels} ch") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(track.id) {
                detectTapGestures(onTap = { onClick() })
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isSelected) "● " else "○ ",
            color = if (isSelected) Color.White else Color.LightGray
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = primary, color = Color.White, fontWeight = FontWeight.Medium)
            if (meta.isNotEmpty()) {
                Text(text = meta, color = Color.LightGray, fontSize = 12.sp)
            }
        }
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
    audioTrackCount: Int = 0,
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
            // Suffix with "× N" when the demuxer enumerated more than
            // one audio track in the program — confirms the producer
            // is sending separate PIDs (and the AUDIO picker should
            // appear in the bottom bar).
            if (audioTrackCount > 1) append("  × ").append(audioTrackCount).append(" tracks")
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

@Composable
private fun SyncIndicator(
    state: PlayerSyncController.State,
    lagMs: Long?,
    modifier: Modifier = Modifier
) {
    val label: String
    val color: Color
    when (state) {
        PlayerSyncController.State.LOCKED -> {
            label = "LOCKED"
            color = Color(0xFF9BE39B)
        }
        PlayerSyncController.State.CATCHING_UP -> {
            label = "CATCHING UP"
            color = Color(0xFFFFD166)
        }
        PlayerSyncController.State.SLOWING_DOWN -> {
            label = "SLOWING DOWN"
            color = Color(0xFFFFD166)
        }
        PlayerSyncController.State.NO_SEI -> {
            label = "NO SEI"
            color = Color.White.copy(alpha = 0.5f)
        }
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (lagMs != null && state != PlayerSyncController.State.NO_SEI) {
            Text(
                text = "Lag: ${lagMs}ms",
                color = Color.White,
                fontSize = 11.sp
            )
            Text(
                text = "  ·  ",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
