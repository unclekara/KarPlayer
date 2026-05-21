package com.karplayer.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karplayer.srt.SrtMode

/**
 * Stripped-down landing screen tuned for Android TV: a big Connect button
 * that reuses the last-saved config and a Settings entry for editing the
 * details. Avoids dragging the user through every form field with a popping
 * IME when all they want to do is press OK and watch the stream.
 *
 * The current target is shown as a non-editable summary above the buttons
 * so it's obvious what Connect will do.
 */
@Composable
fun QuickConnectScreen(
    config: ConnectionConfig,
    onConnect: () -> Unit,
    onSettings: () -> Unit
) {
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    val connectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { connectFocus.requestFocus() }

    val effectiveHost = when {
        config.mode == SrtMode.LISTENER && config.host.isBlank() -> "0.0.0.0"
        else -> config.host.ifBlank { "—" }
    }
    val target = "$effectiveHost : ${config.port}"
    val modeLabel = when (config.mode) {
        SrtMode.CALLER -> "Caller"
        SrtMode.LISTENER -> "Listener"
        SrtMode.RENDEZVOUS -> "Rendezvous"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Faded SMPTE test-pattern backdrop. Recognisable but low enough in
        // alpha that it does not fight the foreground for attention.
        Image(
            painter = painterResource(R.drawable.quick_bg_test_pattern),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.15f)
        )

        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {

        // Top-aligned + scrollable so the Settings button never gets pushed
        // off the screen when the device is rotated to landscape (short
        // vertical extent). On portrait the natural top-padding leaves the
        // title comfortably below the status bar.
        //
        // Note: the IconButton (About) is rendered AFTER this Column so the
        // pointer-hit-test lands on it first — otherwise a full-width Column
        // sits on top of the top-right icon and swallows taps.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 40.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "KarPlayer SRT",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SummaryRow("Target", target)
                    SummaryRow("Mode", modeLabel)
                    SummaryRow("Latency", "${config.latencyMs} ms")
                    if (config.passphrase.isNotEmpty()) {
                        SummaryRow("Encryption", "AES (passphrase set)")
                    }
                    if (config.streamId.isNotEmpty()) {
                        SummaryRow("Stream ID", config.streamId)
                    }
                }
            }

            FocusableButton(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .focusRequester(connectFocus)
            ) { Text("Connect", fontSize = 20.sp) }

            FocusableOutlinedButton(
                onClick = onSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                // Inherit Icon tint from the button's content colour so it
                // flips to black when focused, just like the label below it.
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = LocalContentColor.current
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text("Settings…", fontSize = 16.sp)
            }
        }

        // Drawn last so it stays on top of the scroll Column and receives
        // taps in the top-right corner.
        FocusableIconButton(
            onClick = { showAbout = true },
            unfocusedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "About",
                modifier = Modifier.size(28.dp)
            )
        }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
