# KarPlayer — API reference

KarPlayer is an Android SRT receiver. Unlike KarRelay it has no
network control plane — its public "API" surface is:

1. The **SRT byte protocol** it expects on the wire (TS over SRT,
   plus optional SEI markers).
2. The **module-level Kotlin/JNI contracts** that compose the app.
3. The **persisted user settings** (`SharedPreferences` schema).
4. The **logcat tags** used by each module for ops / debugging.

This document is the integration reference for anyone wanting to
plug a producer (KarRelay, vMix, OBS, FFmpeg) into KarPlayer or to
add a new player feature without breaking the existing modules.

Codebase: Kotlin 2.0, Media3 / ExoPlayer 1.3.1, libsrt 1.5.4 (mbedtls),
NDK r27, minSdk 26.

---

## 1. Wire protocols KarPlayer reads

### 1.1 SRT transport

* Library: libsrt 1.5.4, built from source per ABI
  ([srt/src/main/cpp/CMakeLists.txt](srt/src/main/cpp/CMakeLists.txt)).
* Modes supported (`SrtMode`): `CALLER`, `LISTENER`, `RENDEZVOUS`.
* `SRTO_TRANSTYPE = SRTT_LIVE` set first — establishes message-API,
  TSBPD, packet-drop, 1316-byte payload as the negotiated baseline
  with the peer.
* `SRTO_RCVLATENCY` and `SRTO_PEERLATENCY` set to the same value
  (`SrtOptions.latency`) — caps the negotiated TSBPD window from
  our side.
* `SRTO_MAXBW`, `SRTO_INPUTBW`, `SRTO_STREAMID`, `SRTO_CONNTIMEO` —
  exposed via `SrtOptions`. See
  [srt_jni.cpp](srt/src/main/cpp/srt_jni.cpp).
* `SRTO_PASSPHRASE` + `SRTO_PBKEYLEN` (16/24/32) — exposed for AES
  encryption.

### 1.2 MPEG-TS payload

* Demuxed by Media3 `TsExtractor` with
  `MODE_SINGLE_PMT`, `FLAG_ALLOW_NON_IDR_KEYFRAMES`,
  `FLAG_DETECT_ACCESS_UNITS`. See
  [SrtMediaSourceFactory.kt](player/src/main/kotlin/com/karplayer/player/SrtMediaSourceFactory.kt).
* Video: H.264 and H.265. **H.264 is forced to the software AVC
  decoder on Pixel 8/9** to dodge the Exynos AVC firmware bug — see
  [LowLatencyRenderersFactory.kt](player/src/main/kotlin/com/karplayer/player/LowLatencyRenderersFactory.kt).
* Audio: AAC ADTS / LATM, MP3.

### 1.3 KarSEI-TSYNC (clock-sync mark)

Detected by
[SeiTimecodeParser.kt](player/src/main/kotlin/com/karplayer/player/SeiTimecodeParser.kt).

```
4B 61 72 53 45 49 2D 54 53 59 4E 43 00 00 00 00     ("KarSEI-TSYNC\0\0\0\0")
<8 bytes>   UTC microseconds, big-endian
```

The parser scans the raw SRT byte stream for the 16-byte UUID,
carries up to 24 bytes between successive `feed()` calls so a
UUID split across two SRT reads is still recovered, and emits
each found timestamp through `onSeiTimestamp(seiMicros)` on the
**ExoPlayer Loader thread**. The PlayerManager bounces that
callback onto the main thread before touching ExoPlayer.

### 1.4 KarSEI-LCNS (dropped)

Originally planned: a second SEI carrying an Ed25519 token that the
player would verify natively and use to suppress a bouncing watermark.
Removed — KarPlayer no longer participates in licensing. The relay
enforces Pro features on the locally-cached envelope; the player just
reflects what arrives. See
[../KarRelay/LICENSING.md](../KarRelay/LICENSING.md).

### 1.5 Relay clock-sync HTTP

[RelayClockSync.kt](player/src/main/kotlin/com/karplayer/player/RelayClockSync.kt)
hits `http://<host>:<webPort>/api/time` (default port 8080)
exactly as KarRelay implements it
([KarRelay API.md → /api/time](../KarRelay/API.md)).

* 5 NTP-style samples, picks the one with the lowest round-trip.
* Returns `phoneMid − relayUnixMs` as the offset.
* `usesCleartextTraffic="true"` in `AndroidManifest.xml` —
  otherwise `targetSdk=34` silently blocks the request.

---

## 2. Module surface

Four Gradle modules:

```
:app      Application entry, MainActivity, launcher icon, manifest, theme.
:srt      JNI bridge to libsrt + Media3 DataSource over SRT.
:player   ExoPlayer wiring, SRT pipeline, SEI sync, license cache.
:ui       Compose UI screens, ViewModel, focusable controls.
```

`:app → :ui → :player → :srt` (linear dep graph, no cycles).

### 2.1 `:srt` — SRT primitives (consumed by `:player`)

[SrtNative.kt](srt/src/main/kotlin/com/karplayer/srt/SrtNative.kt) —
JNI external functions, all signatures must stay in sync with
[srt_jni.cpp](srt/src/main/cpp/srt_jni.cpp).

```kotlin
internal object SrtNative {
    external fun nativeCreate(): Long
    external fun nativeConnect(
        handle:     Long,
        host:       String,
        port:       Int,
        latencyMs:  Int,
        maxBwBps:   Long,
        inputBwBps: Long,
        mode:       Int,         // 0=CALLER, 1=LISTENER, 2=RENDEZVOUS
        streamId:   String,
        timeoutMs:  Int,
        passphrase: String,
        pbkeylen:   Int          // 0=auto, 16/24/32
    ): Long                       // ≥0 = handle to use; <0 = KP_ERR_* sentinel
    external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeClose(handle: Long)
    external fun nativeGetStats(handle: Long, out: DoubleArray): Int
}
```

`nativeConnect` returns the **active** socket handle so listener
mode can transparently replace the bound socket with the accepted
peer socket. Negative return values map to `KP_ERR_*` constants
defined in `SrtNative`.

[SrtOptions.kt](srt/src/main/kotlin/com/karplayer/srt/SrtOptions.kt):

```kotlin
enum class SrtMode { CALLER, LISTENER, RENDEZVOUS }

data class SrtOptions(
    val latency:        Int    = 120,
    val maxBandwidth:   Long   = -1L,     // -1 = AUTO; 0 = unlimited; >0 = bytes/s
    val inputBandwidth: Long   = 0L,
    val mode:           SrtMode = SrtMode.CALLER,
    val streamId:       String = "",
    val timeout:        Int    = 3000,
    val passphrase:     String = "",
    val pbkeyLen:       Int    = 0         // 0 = peer-driven
)
```

[SrtSocket.kt](srt/src/main/kotlin/com/karplayer/srt/SrtSocket.kt) —
thread-safe wrapper. Exposes `stats: StateFlow<SrtStats>` polled
every 500 ms via `srt_bistats`. Reads use an internal
`SRT_MAX_PAYLOAD = 1500` buffer to bridge SRT's message-API
(returns one full payload per call) with Media3's variable-length
`DataSource.read` requests.

[SrtDataSource.kt](srt/src/main/kotlin/com/karplayer/srt/SrtDataSource.kt) —
implements `androidx.media3.datasource.DataSource`. URI form is
`srt://host:port`; the path is ignored. Exposes its own
non-null `stats: StateFlow<SrtStats>` so consumers can subscribe
before `open()` is called.

### 2.2 `:player` — ExoPlayer orchestration

#### [PlayerManager.kt](player/src/main/kotlin/com/karplayer/player/PlayerManager.kt) — single source of truth

```kotlin
class PlayerManager(context: Context) {

    /** Holds the current ExoPlayer instance. The reference can change when
     *  the LoadControl needs to be reconfigured. Re-read on every access. */
    val player: ExoPlayer

    // --- live state, all StateFlow ---
    val playerState:    StateFlow<PlayerState>          // IDLE / CONNECTING / BUFFERING / PLAYING / RECONNECTING / ERROR
    val stats:          StateFlow<SrtStats>             // RTT, bitrate, loss, jitter, retx
    val lastError:      StateFlow<String?>
    val mediaInfo:      StateFlow<MediaInfo>            // codec / resolution / fps / sample rate
    val reconnectAttempt: StateFlow<Int>
    val isInPip:        StateFlow<Boolean>
    val syncState:      StateFlow<PlayerSyncController.State?>  // null when SEI sync off
    val measuredLagMs:  StateFlow<Long?>
    val exitToMenu:     SharedFlow<String?>             // emitted when 3 reconnects fail
    fun clearError()                                    // dismiss the menu reason banner

    fun connect(
        host:               String,
        port:               Int,
        options:            SrtOptions,
        useSoftwareDecoder: Boolean = false,
        maxBufferMs:        Int = 1500,
        seiSync:            PlayerSyncController.SeiSyncConfig? = null,
        liveEdgeTargetMs:   Int = 0,                    // 0 = disabled; >0 = LOW_LATENCY catch-up target
        relayHttpPort:      Int = 8484                  // KarRelay service port (default changed in 0.3)
    )

    fun disconnect()
    fun onAppResumed()          // call on Activity ON_RESUME
    fun setSurface(s: Surface?)
    fun setInPipMode(value: Boolean)
    fun release()               // call from Application.onTerminate
}
```

Behaviour notes:

* `connect()` is the single entry point. It rebuilds the ExoPlayer
  instance only if `maxBufferMs` changes (LoadControl is immutable).
  The previous instance is released asynchronously on the main thread.
* `seiSync != null` activates `PlayerSyncController` and wires
  `SeiAwareDataSource` so the byte stream is parsed for SEI marks
  inline.
* `liveEdgeTargetMs > 0` activates the LOW_LATENCY catch-up
  coroutine — soft `setPlaybackSpeed` nudges to keep
  `(bufferedPosition − currentPosition)` near the target. No `seekTo`
  is ever called — that would force a new SRT handshake.
* `relayHttpPort` only matters when `seiSync != null` (drives
  `RelayClockSync.estimate` for the clock offset). The clock-offset
  estimate is attempted before the session starts (up to 3 × 1 s
  retries); if it fails the session starts anyway and a background
  retry continues to refine the offset.
* `onAppResumed()` does a forced reconnect — *unless* the activity
  is in or has recently exited PiP (2 s guard) — so the back-buffer
  doesn't accumulate while the app was backgrounded.

#### [PlayerSyncController.kt](player/src/main/kotlin/com/karplayer/player/PlayerSyncController.kt)

```kotlin
class PlayerSyncController(player: ExoPlayer, config: SeiSyncConfig) {

    data class SeiSyncConfig(
        val targetLagMs:        Long,
        val deadbandMs:         Long,
        val maxSpeedAdjustPct:  Int,                    // 0..50
        val clockOffsetMs:      Long = 0L
    )

    enum class State { NO_SEI, LOCKED, CATCHING_UP, SLOWING_DOWN }

    val state:          StateFlow<State>
    val measuredLagMs:  StateFlow<Long?>

    fun onSeiTimestamp(seiMicros: Long)
    fun checkStaleness()                                // call from a 1 Hz timer
    fun updateClockOffset(clockOffsetMs: Long)
    fun reset()
}
```

Lag formula: `lag = (now − seiMicros) − clockOffset + (buffered − currentPosition)`.
This is the **rendered-frame lag**, not the data-source-input lag, so
LOCKED actually means "what the user sees matches the target".

Pure decision function `decideSpeed(lagMs, cfg) → SpeedDecision`
is exposed in the companion object for unit testing without an
ExoPlayer.

#### [RelayClockSync.kt](player/src/main/kotlin/com/karplayer/player/RelayClockSync.kt)

```kotlin
internal object RelayClockSync {
    data class Sample(
        val offsetMs:    Long,                          // phoneMid − relayMs
        val rttMs:       Long,
        val relayMs:     Long,
        val phoneMidMs:  Long
    )

    suspend fun estimate(
        host:     String,
        webPort:  Int = 8080,
        attempts: Int = 5
    ): Sample?
}
```

`host = "0.0.0.0"` / empty → returns null without attempting (handles
Listener mode where we don't have a peer IP yet).

#### [SeiTimecodeParser.kt](player/src/main/kotlin/com/karplayer/player/SeiTimecodeParser.kt)

```kotlin
internal class SeiTimecodeParser {
    fun feed(buf: ByteArray, off: Int, len: Int): List<Long>   // UTC microseconds
    fun reset()
    companion object { val UUID_TSYNC: ByteArray }
}
```

Thread affinity: single-threaded, owned by one `SeiAwareDataSource`.
Carry buffer capped at `UUID.size + 8 = 24` bytes.

#### [SeiAwareDataSource.kt](player/src/main/kotlin/com/karplayer/player/SeiAwareDataSource.kt)

Decorator around `SrtDataSource` (or any `DataSource`). Forwards
SEI timestamps via `onTimestamp: (Long) -> Unit`. Selected via the
`onSeiTimestamp` constructor arg on `SrtDataSourceFactory`; when
null the inner data source is used directly with zero overhead.

#### [LowLatencyRenderersFactory.kt](player/src/main/kotlin/com/karplayer/player/LowLatencyRenderersFactory.kt)

Custom `DefaultRenderersFactory`:

* Opts into `MediaFormat.KEY_LOW_LATENCY = 1` only when the chosen
  decoder advertises `MediaCodecCapabilities.FEATURE_LowLatency`
  *and* the codec MIME is **not** `video/avc`. Defensive carve-out
  for the Exynos AVC Pixel 8/9 quirk regardless of capability flag.
* Optional `forceSoftwareDecoder: () -> Boolean` constructor arg —
  when it returns true, the codec candidate list is filtered to
  `info.softwareOnly == true`. Hooked to the user-visible "Use
  software decoder" toggle.

#### [MediaInfo.kt](player/src/main/kotlin/com/karplayer/player/MediaInfo.kt)

```kotlin
data class MediaInfo(
    val videoCodec:      String?,
    val videoWidth:      Int,
    val videoHeight:     Int,
    val videoFrameRate:  Float,
    val audioCodec:      String?,
    val audioSampleRate: Int,
    val audioChannels:   Int
) {
    val aspectRatio: Float                              // 0 if dimensions unknown
}
```

### 2.3 `:ui` — Compose surface

* [`KarPlayerNavRoot`](ui/src/main/kotlin/com/karplayer/ui/KarPlayerNavRoot.kt) —
  navigates between `Screen.QUICK / SETTINGS / PLAYER`. Holds
  `currentConfig` across screen switches so settings survive a
  PLAYER round-trip.
* [`PlayerViewModel`](ui/src/main/kotlin/com/karplayer/ui/PlayerViewModel.kt) —
  thin mapper between `ConnectionConfig` (UI) and `PlayerManager.connect`.
  All player state is exposed as upstream `StateFlow` re-exports.
* [`ConnectionConfig`](ui/src/main/kotlin/com/karplayer/ui/ConnectionConfig.kt) —
  user-facing config bag, persisted to `SharedPreferences` via
  `ConnectionConfigStore`. See **§3** for the wire schema.
* TV-specific affordances live in [`Platform.kt`](ui/src/main/kotlin/com/karplayer/ui/Platform.kt)
  (`isTvDevice()`), [`TvAwareTextField.kt`](ui/src/main/kotlin/com/karplayer/ui/TvAwareTextField.kt)
  (suppress IME until OK on TV), [`FocusableControls.kt`](ui/src/main/kotlin/com/karplayer/ui/FocusableControls.kt)
  (white-on-black focus state for buttons / chips / switches).
* [`NumericStepperField.kt`](ui/src/main/kotlin/com/karplayer/ui/NumericStepperField.kt) —
  shared input widget: `− <text-field> +` row + optional slider
  (hidden on TV by default since sliders don't D-pad well).

---

## 3. Persisted config schema

`SharedPreferences` file `karplayer_prefs`. Mapped 1:1 by
`ConnectionConfigStore`. All values default-able; missing keys are
filled in from `ConnectionConfig()`'s constructor defaults.

| Key | Type | Default | Notes |
|---|---|---|---|
| `host` | String | "" | Remote IP (Caller) or bind address (Listener) |
| `port` | Int | 9000 | SRT port |
| `latency` | Int | 120 | `SRTO_RCVLATENCY/PEERLATENCY` ms |
| `stream_id` | String | "" | `SRTO_STREAMID` |
| `mode` | String enum | `CALLER` | `CALLER`, `LISTENER`, `RENDEZVOUS` |
| `maxbw_mode` | String enum | `AUTO` | `AUTO`, `UNLIM`, `FIXED` |
| `maxbw_mbps` | Int | 50 | Mbps cap when `maxbw_mode = FIXED` |
| `passphrase` | String | "" | AES passphrase (10..79 chars when non-empty) |
| `pbkeylen` | Int | 0 | 0, 16, 24, 32 |
| `software_decoder` | Bool | false | Force software-only decoders |
| `sync_mode` | String enum | `OFF` | `OFF`, `LOW_LATENCY`, `SEI_SYNC` |
| `max_buffer_ms` | Int | 400 | LoadControl maxBufferMs (LOW_LATENCY) |
| `target_lag_ms` | Int | 250 | SEI_SYNC target lag |
| `sync_deadband_ms` | Int | 50 | SEI_SYNC speed-control deadband |
| `max_speed_adjust_pct` | Int | 5 | SEI_SYNC speed range cap (0..50) |
| `relay_http_port` | Int | 8080 | KarRelay /api/time port |

---

## 4. Logcat tags

| Tag | Module | Use |
|---|---|---|
| `KarPlayer` | `:player` | session lifecycle, buffer sizing, clock sync, low-latency speed nudges, reconnect outcome |
| `karplayer-srt` | `:srt` (native) | libsrt init / connect / read errors, negotiated `SRTO_RCVLATENCY` after handshake |
| `ExoPlayerImpl` | Media3 | upstream player logs (DEBUG → very chatty) |

Useful filter for live ops:

```
adb logcat KarPlayer:I karplayer-srt:I ExoPlayerImplInternal:E AndroidRuntime:E *:S
```

---

## 5. SyncMode → PlayerManager wiring

The UI's `SyncMode` enum is translated in
[`PlayerViewModel.connect`](ui/src/main/kotlin/com/karplayer/ui/PlayerViewModel.kt):

| SyncMode | `maxBufferMs` | `seiSync` | `liveEdgeTargetMs` |
|---|---|---|---|
| `OFF` | 1500 | null | 0 |
| `LOW_LATENCY` | `cfg.maxBufferMs` | null | `cfg.maxBufferMs / 2`, ≥ 100 |
| `SEI_SYNC` | 600 | `SeiSyncConfig(targetLag, deadband, maxAdjust)` | 0 |

---

## 6. Licensing posture

KarPlayer is **not** part of the activation/licensing chain — no
Ed25519 verification, no LCNS-SEI parsing, no licensing client. Pro
features (SEI timecode emission, viewer limits, etc.) are enforced
entirely inside KarRelay; KarPlayer simply renders what the relay
sends and reflects rejection reasons coming over SRT / the HTTP
side-channel (section 7 below).

See [../KarRelay/LICENSING.md](../KarRelay/LICENSING.md) and
[../KarLicense/PLAN.md](../KarLicense/PLAN.md).

---

## 7. Connection-rejection contract (added in 0.3)

Two channels carry the reason for a refused or dropped session.

### 7.1 SRT handshake rejection

The relay sets a standard SRT handshake rejection code; KarPlayer
reads it natively:

* `srt_jni.cpp` stores `srt_getrejectreason(sock)` in
  `std::atomic<int> g_lastRejectReason` on connect failure.
* JNI: `SrtNative.nativeLastRejectReason(): Int`.
* `SrtSocket.open()` throws **`SrtConnectException(rejectReason, …)`**
  (subclass of `IOException`) — propagates through Media3 as the
  cause; `PlayerManager.findSrtConnectException()` walks the chain.

Recognised codes (gosrt and libsrt share the wire encoding):

| Code | Meaning                            | Player message                                   |
|------|------------------------------------|--------------------------------------------------|
| 1403 | REJX_FORBIDDEN — streamid blocked  | "Wrong stream ID — not allowed for this source." |
| 1402 | REJX_OVERLOAD — viewer-limit hit   | "Relay is on the free plan: max 3 viewers…"     |
| else | Network / generic                   | `error.message` ?: `errorCodeName`              |

### 7.2 HTTP side-channel (mid-session kicks)

SRT carries no close reason. The player asks the relay over HTTP —
same host, port from `ConnectionConfig.relayHttpPort` (default 8484):

```
GET http://<host>:<port>/api/disconnect-reason?streamid=<urlenc>
→ { "reason": "kicked"|"viewer_limit"|"wrong_stream_id"|null,
    "at": <unix>, "message": "…" }
```

Implementation: `RelayClockSync.disconnectReason()`. The relay keeps a
60-second sliding window of events.

### 7.3 Reconnect policy

`PlayerManager.scheduleReconnect()` runs up to
`MAX_RECONNECT_ATTEMPTS = 3` attempts with exponential backoff. An
attempt *succeeds* only when ExoPlayer reaches `PLAYING` —
`BUFFERING` appears immediately after `prepare()` and is **not**
counted (this was a 0.3 bug fix; before, every reconnect looked
successful and the counter never reached 3). A bumped `errorEpoch`
(incremented in every `onPlayerError`) makes instant rejections fail
fast without waiting the per-attempt timeout.

On exhaustion the manager emits `exitToMenu: SharedFlow<String?>`
with the last error; `KarPlayerNavRoot` observes it and navigates
back to `Screen.QUICK`. The reason is shown there briefly and
auto-dismissed after 8 s (`PlayerManager.clearError()`) so the app
never carries a permanent "limited" banner.

### 7.4 Persisted settings change (0.3)

```
relay_http_port: Int    # default 8484 (was 8080), persisted as KEY_RELAY_HTTP_PORT
```

UI: "Relay service port" field in the **Connection** section
(previously hidden behind SEI_SYNC mode).

---

## Author

Alexander Karabatov · <hellokarabatov@gmail.com>
