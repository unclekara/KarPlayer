package com.karplayer.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

internal object RelayClockSync {

    data class Sample(
        val offsetMs: Long,
        val rttMs: Long,
        val relayMs: Long,
        val phoneMidMs: Long
    )

    suspend fun estimate(
        host: String,
        webPort: Int = DEFAULT_WEB_PORT,
        attempts: Int = DEFAULT_ATTEMPTS
    ): Sample? = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || cleanHost == "0.0.0.0") return@withContext null

        val url = URL("http://${formatHost(cleanHost)}:$webPort/api/time")
        var best: Sample? = null
        repeat(attempts.coerceAtLeast(1)) {
            val sample = runCatching { sample(url) }
                .onFailure { err ->
                    Log.w(TAG, "clockSync: sample failed url=$url error=${err.message}")
                }
                .getOrNull()
            if (sample != null && (best == null || sample.rttMs < best!!.rttMs)) {
                best = sample
            }
        }
        best?.also {
            Log.i(
                TAG,
                "clockSync: host=$cleanHost webPort=$webPort offset=${it.offsetMs}ms " +
                        "rtt=${it.rttMs}ms relayMs=${it.relayMs} phoneMidMs=${it.phoneMidMs}"
            )
            if (abs(it.offsetMs) > WARN_OFFSET_MS) {
                Log.w(TAG, "clockSync: large phone-minus-relay offset ${it.offsetMs}ms")
            }
        }
    }

    private fun sample(url: URL): Sample {
        val t0 = System.currentTimeMillis()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val relayMs = JSONObject(body).getLong("unix_ms")
            val t1 = System.currentTimeMillis()
            val rttMs = t1 - t0
            val phoneMidMs = t0 + rttMs / 2L
            return Sample(
                offsetMs = phoneMidMs - relayMs,
                rttMs = rttMs,
                relayMs = relayMs,
                phoneMidMs = phoneMidMs
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun formatHost(host: String): String {
        return if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    }

    private const val TAG = "KarPlayer"
    private const val DEFAULT_WEB_PORT = 8080
    private const val DEFAULT_ATTEMPTS = 5
    private const val TIMEOUT_MS = 300
    private const val WARN_OFFSET_MS = 1_000L
}
