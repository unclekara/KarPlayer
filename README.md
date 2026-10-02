# KarPlayer - SRT Receiver for Android devices

Low-latency Android receiver for live MPEG-TS over SRT. Built for
professional live production: handshake, decode, surface — minimum
moving parts, end-to-end target **< 200 ms on LAN**.

[SRT](https://github.com/Haivision/srt) (Secure Reliable Transport) is an
open-source video transport protocol created and open-sourced by
[Haivision](https://www.haivision.com/).

- **libsrt 1.5.4** with AES encryption (via mbedtls 3.6.2), built from source per ABI
- **Media3 / ExoPlayer 1.11.1** with MPEG-TS extractor and hardware H.264 / H.265 decode
- **Kotlin 2.2 + Jetpack Compose** UI; minSdk 26, compile 36, target 34
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- **16 KB page-size aligned** (Android 15+ requirement)

## Status

Verified end-to-end against vMix (HEVC + AAC) and against the companion
**[KarRelay](../KarRelay)** (SEI timecode sync, rejection reasons,
operator kicks). Encryption (PBKEYLEN 128/192/256), receiver-side TSBPD
latency, bandwidth cap, and stream-ID are all wired through. Runs on
phones, tablets and Android TV / leanback launchers.

Current version: **0.6** — see [CHANGELOG.md](CHANGELOG.md). For module
layout and data flows see [ARCHITECTURE.md](ARCHITECTURE.md); for
on-the-wire and inter-module contracts see [API.md](API.md).

## Features

### Transport
- **Caller, Listener, Rendezvous** modes — pick the one that matches your sender
- Adjustable receiver latency (slider 20–1000 ms, manual input up to 8000 ms)
- AES-128/192/256 passphrase, key-length selectable
- Live stats overlay: RTT, bitrate, packet loss (colour-coded), jitter, retx count
- Codec / resolution / sample-rate readout from `Player.Listener.onTracksChanged`
- Aspect-ratio auto-detect

### Sync modes
- **OFF** — plain playback at the negotiated latency.
- **LOW_LATENCY** — speed-nudge live-edge chaser (1.03× / 1.06×) without
  `seekTo()` (which would reset the SRT loader).
- **SEI_SYNC** — frame-accurate lock to the relay's timeline using a
  `KarSEI-TSYNC` SEI carried in the TS. NTP-style HTTP clock-sync to the
  relay aligns phone↔relay clocks first; the controller then holds a
  user-configurable target lag via small speed adjustments.
  Requires a Pro-licensed KarRelay to emit TSYNC.

### Resilience
- **Bounded auto-reconnect** (3 attempts, exponential backoff). After three
  failures the player returns to the connection menu; the reason is shown
  briefly (auto-dismissed) so the app never looks "limited".
- **Connection-rejection reasons** surfaced from SRT and from a relay HTTP
  side-channel: *wrong stream id* (`SRT_REJX_FORBIDDEN`), *viewer limit*
  (`SRT_REJX_OVERLOAD`), *operator kick* (`/api/disconnect-reason`).
- Faster reconnect on Wi-Fi roaming via `ConnectivityManager.NetworkCallback`.
- Lifecycle-driven reconnect on resume (skipped during PiP transitions).

### Platform
- **Picture-in-Picture** — playback survives Home / Recents.
- **AudioFocus** integration (auto-pause on call, ducking, BT routing,
  Media-volume rocker).
- **Wi-Fi performance lock** during a session.
- **Software-decoder toggle**; `c2.exynos.avc.decoder` auto-excluded on
  Pixel 8/9 to dodge the green-tearing/freeze bug on live H.264.
- **Android TV / leanback launcher**, D-pad-first focus order, no IME spam.
- Immersive fullscreen, lock mode (long-press), swipe brightness / volume.
- **Kiosk mode** (opt-in) for unattended screens: no overlays at all, black
  screen while off air, reconnect never gives up, optional connect-on-launch.
  Double-BACK to leave.

## Configuration

All settings live in the in-app **Settings** screen and are persisted
across launches (`SharedPreferences` — see `ConnectionConfig`).

| Setting             | Default       | Notes                                                          |
|---------------------|---------------|----------------------------------------------------------------|
| Mode                | Caller        | Caller / Listener / Rendezvous                                 |
| Host                | —             | Required in Caller / Rendezvous mode                           |
| Port                | —             | SRT UDP port                                                   |
| Stream ID           | (empty)       | Optional, sender-defined                                       |
| Relay service port  | **8484**      | KarRelay's web/HTTP port — used for clock sync and the disconnect-reason channel. Must match `web_port` in the relay |
| Receiver latency    | 120 ms        | Slider 20–1000 ms, manual up to 8000 ms                        |
| Bandwidth limit     | Auto          | Optional fixed cap (Mbps)                                      |
| Passphrase / PBKEYLEN | (empty)     | AES-128 / 192 / 256                                            |
| Sync mode           | OFF           | OFF / LOW_LATENCY / SEI_SYNC                                   |
| Software decoder    | Off (auto-on on buggy HW) | Manual override for the HW decoder            |
| Kiosk mode          | Off           | Unattended screens: no chrome, black while off air, unbounded reconnect |
| Kiosk autostart     | Off           | Connect on app launch (shown only when Kiosk mode is on) |

The relay service port default changed from 8080 to 8484 in 0.3 — see
[CHANGELOG.md](CHANGELOG.md).

## Repository layout

```
KarPlayer/
├── app/        Application module, DI, MainActivity, launcher icon
├── srt/        JNI bridge + libsrt + mbedtls (built via ExternalProject_Add)
├── player/     ExoPlayer integration, LoadControl, MediaInfo state, auto-reconnect
└── ui/         Compose: ConnectionScreen, PlayerScreen, ViewModel
```

## Build

### Requirements

- **JDK 17** (OpenJDK / Temurin)
- **Android SDK** with:
  - `platforms;android-36`
  - `build-tools;34.0.0`
  - `ndk;27.3.13750724` (NDK r27 or newer — required for 16 KB-aligned `libc++_shared.so`)
  - `cmake;3.22.1`
- **Linux / macOS / WSL2** recommended. Native windows builds work too if your
  CMake / NDK paths are POSIX-clean.

### First build

```bash
./gradlew :app:assembleDebug
```

Note the **first build is slow (~10 min)**: libsrt and mbedtls are cloned
shallow from upstream at the tags pinned in
`srt/src/main/cpp/CMakeLists.txt` and cross-compiled per ABI. Subsequent
builds reuse the cached EP artifacts.

### Release APK

```bash
./gradlew :app:assembleRelease
```

Release signing is loaded from `keystore.properties` at the repo root
(gitignored). To produce a signed release on a fresh checkout, generate
your own keystore and create the properties file:

```bash
keytool -genkeypair -keystore karplayer-release.keystore \
    -alias karplayer -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Your Name, O=KarPlayer, C=US"

cat > keystore.properties <<EOF
storeFile=karplayer-release.keystore
storePassword=<your-password>
keyAlias=karplayer
keyPassword=<your-password>
EOF
```

If `keystore.properties` is absent, release builds fall back to the standard
Android debug keystore so local development still works — but you cannot
distribute that APK publicly.

R8 minification is disabled by default; the file `app/proguard-rules.pro`
is a placeholder for when you turn it on.

### Switching libsrt / mbedtls versions

Edit the `SRT_VERSION` / `MBEDTLS_VERSION` values in
`srt/src/main/cpp/CMakeLists.txt` (or override `-DSRT_VERSION=...` in
`srt/build.gradle.kts`). Then either:

```bash
rm -rf srt/.cxx srt/build   # full reset
# or just the ExternalProject caches:
rm -rf srt/.cxx/Debug/*/srt-ep srt/.cxx/Debug/*/mbedtls-ep
```

…and rebuild.

## Test it with FFmpeg

### App as Caller (sender is the listener)

```bash
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac \
       -f mpegts "srt://0.0.0.0:9000?mode=listener&latency=120"
```

In the app: Mode = **Caller**, Host = your machine's LAN IP, Port = 9000,
Latency = 120.

### App as Listener (sender connects to us)

```bash
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac \
       -f mpegts "srt://<phone-ip>:9000?mode=caller&latency=120"
```

In the app: Mode = **Listener**, leave Host empty (binds 0.0.0.0), Port = 9000.
The ConnectionScreen shows the phone's LAN IP under the host field — point
the sender at it. This is the right configuration for production setups
like vMix, where the encoder pushes to the phone.

## Architecture notes

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full module breakdown. The
non-obvious bits worth highlighting here:

- **`SrtDataSource` ↔ ExoPlayer**: SRT live mode delivers fixed 1316-byte
  payloads; Media3 extractors read arbitrary lengths. An internal `rxBuf`
  in `SrtSocket.kt` absorbs the mismatch.
- **TRANSTYPE first**: `SRTO_TRANSTYPE = SRTT_LIVE` is set before any other
  option (bulk-presets MESSAGEAPI / TSBPDMODE / TLPKTDROP / PAYLOADSIZE).
- **Reconnect**: bounded to 3 attempts. Success = reaching `PLAYING`
  (`BUFFERING` doesn't count — it appears immediately after `prepare()`
  and would otherwise mask a failing attempt).
- **State race fix**: ExoPlayer's `STATE_IDLE` event from `player.stop()`
  during `disconnect()` is ignored while `CONNECTING`/`RECONNECTING`.
- **16 KB alignment**: native `.so` linked with `-Wl,-z,max-page-size=16384`
  for Android 15+.

## Known limitations / future work

- **No stream recording** (writing the received MPEG-TS to disk).
- **No multipath / bonding** on RX (libsrt bonding is compiled in but unused).
- **`pktRcvRetransTotal`** is unavailable in this libsrt minor — we report
  the sender-side counter (`pktRetransTotal`) as a stand-in.
- **Jitter** in the overlay is a proxy (`msRcvBuf` from `SRT_TRACEBSTATS`);
  true per-packet PCR jitter would have to be measured on the TS layer.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Third-party components and their licenses are listed in [NOTICE](NOTICE).

## Development

This project was developed with extensive AI assistance (Anthropic Claude).
Architecture decisions, the libsrt + mbedtls integration, the JNI layer, the
Compose UI, and most of the iteration on protocol-level issues happened in
pair-programming sessions. Every commit carries a `Co-Authored-By` trailer
reflecting that. Human selection, structure, review, and end-to-end
verification on real hardware are mine.

## Author

Alexander Karabatov
