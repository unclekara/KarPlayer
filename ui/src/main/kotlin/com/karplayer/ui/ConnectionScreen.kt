package com.karplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ConnectionScreen(
    initial: ConnectionConfig,
    onConnect: (ConnectionConfig) -> Unit
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var latencyMs by remember { mutableStateOf(initial.latencyMs) }
    var streamId by remember { mutableStateOf(initial.streamId) }
    var maxBwMode by remember { mutableStateOf(initial.maxBwMode) }
    var maxBwMbps by remember { mutableStateOf(initial.maxBandwidthMbps.toFloat()) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var pbkeyLen by remember { mutableStateOf(initial.pbkeyLen) }

    val passphraseError = passphrase.isNotEmpty() && passphrase.length !in 10..79
    val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true
    val canConnect = host.isNotBlank() && portValid && !passphraseError

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "KarPlayer SRT",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp
            )
            IconButton(onClick = { showAbout = true }) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About",
                    tint = Color.White
                )
            }
        }

        Section("Connection") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Host (IPv4)") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = port.isNotEmpty() && !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = streamId,
                onValueChange = { streamId = it },
                label = { Text("Stream ID") },
                placeholder = { Text("optional, sender-defined") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Section("Receiver buffer (SRT latency)") {
            var latencyText by remember(latencyMs) { mutableStateOf(latencyMs.toString()) }
            fun applyLatency(value: Int) {
                val c = value.coerceIn(20, 8000)
                latencyMs = c
                latencyText = c.toString()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { applyLatency(latencyMs - 1) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp)
                ) { Text("−", fontSize = 18.sp) }

                OutlinedTextField(
                    value = latencyText,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() }.take(5)
                        latencyText = cleaned
                        cleaned.toIntOrNull()?.let { latencyMs = it.coerceIn(20, 8000) }
                    },
                    label = { Text("Latency, ms") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = { applyLatency(latencyMs + 1) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp)
                ) { Text("+", fontSize = 18.sp) }
            }
            Slider(
                value = latencyMs.coerceIn(20, 1000).toFloat(),
                onValueChange = { applyLatency(it.roundToInt()) },
                valueRange = 20f..1000f
            )
            HelperText(
                "SRTO_RCVLATENCY — TSBPD jitter buffer on receiver side. " +
                "Rule of thumb: ≥ 2·RTT. 120 ms is fine for LAN; raise to " +
                "200–500 ms over Internet. Slider covers 20–1000 ms; field " +
                "allows up to 8000 ms for extreme cases. Value applies on Connect."
            )
        }

        Section("Bandwidth limit") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaxBwMode.values().forEach { m ->
                    FilterChip(
                        selected = m == maxBwMode,
                        onClick = { maxBwMode = m },
                        label = {
                            Text(
                                when (m) {
                                    MaxBwMode.AUTO -> "Auto"
                                    MaxBwMode.UNLIM -> "Unlimited"
                                    MaxBwMode.FIXED -> "Fixed"
                                }
                            )
                        }
                    )
                }
            }
            HelperText(
                when (maxBwMode) {
                    MaxBwMode.AUTO -> "SRTO_MAXBW = -1. libsrt sizes the return channel " +
                            "automatically from INPUTBW + overhead. Recommended for receivers."
                    MaxBwMode.UNLIM -> "SRTO_MAXBW = 0. No cap on the send-side. Affects only " +
                            "the receiver's ARQ/NAK channel."
                    MaxBwMode.FIXED -> "Hard cap on the send-side. On a pure RX this only " +
                            "limits ACK/NAK traffic (~1% of stream)."
                }
            )
            if (maxBwMode == MaxBwMode.FIXED) {
                LabeledValue(label = "Cap", value = "${maxBwMbps.toInt()} Mbps")
                Slider(
                    value = maxBwMbps,
                    onValueChange = { maxBwMbps = it },
                    valueRange = 1f..200f
                )
            }
        }

        Section("Encryption (AES)") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                placeholder = { Text("empty = no encryption") },
                singleLine = true,
                isError = passphraseError,
                supportingText = {
                    if (passphraseError) {
                        Text("Passphrase must be 10–79 characters")
                    } else {
                        Text("${passphrase.length} chars · must match sender")
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (passphrase.isNotEmpty()) {
                Text(
                    text = "Key length (SRTO_PBKEYLEN)",
                    color = Color.White.copy(alpha = 0.85f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "Auto",
                        16 to "AES-128",
                        24 to "AES-192",
                        32 to "AES-256"
                    ).forEach { (len, label) ->
                        FilterChip(
                            selected = len == pbkeyLen,
                            onClick = { pbkeyLen = len },
                            label = { Text(label) }
                        )
                    }
                }
                HelperText(
                    "Auto = peer-driven (libsrt default is AES-128). " +
                    "Must match the sender's setting."
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (!canConnect) return@Button
                onConnect(
                    ConnectionConfig(
                        host = host,
                        port = port.toInt(),
                        latencyMs = latencyMs,
                        streamId = streamId,
                        maxBwMode = maxBwMode,
                        maxBandwidthMbps = maxBwMbps.toInt(),
                        passphrase = passphrase,
                        pbkeyLen = pbkeyLen
                    )
                )
            },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Connect") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            content()
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White)
        Text(text = value, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}
