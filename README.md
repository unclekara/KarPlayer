# KarPlayer - SRT Receiver for Android devices

Low-latency Android receiver for live MPEG-TS over [SRT](https://github.com/Haivision/srt).
Built for professional live production: handshake, decode, surface — minimum
moving parts, end-to-end target **< 200 ms on LAN**.

- **libsrt 1.5.4** with AES encryption (via mbedtls 3.6.2), built from source per ABI
- **Media3 / ExoPlayer 1.3.1** with MPEG-TS extractor and hardware H.264 / H.265 decode
- **Kotlin 2.0 + Jetpack Compose** UI; minSdk 26, target 34
- **ABIs**: `arm64-v8a`, `x86_64`
- **16 KB page-size aligned** (Android 15+ requirement)

## Status

Verified end-to-end against a vMix SRT listener (HEVC + AAC) on a Pixel 8.
Encryption (passphrase / PBKEYLEN 128/192/256) works against vMix. Receiver-side
TSBPD latency, bandwidth cap, and stream-ID are all wired through.

## Features

- **Caller, Listener, Rendezvous** modes — pick the one that matches your sender
  - Caller: we initiate to the sender's address
  - Listener: we bind a local port and wait for the sender to connect to us
    (UI shows the device's LAN IP so you know where to point the sender)
  - Rendezvous: symmetric NAT-traversal handshake
- Adjustable receiver latency (slider 20–1000 ms, manual input up to 8000 ms)
- AES-128/192/256 passphrase support, key-length selectable
- Live stats overlay: RTT, bitrate, packet loss (colour-coded), jitter, retx count
- Codec / resolution / sample-rate readout from `Player.Listener.onTracksChanged`
- Aspect-ratio auto-detect via `AspectRatioFrameLayout`
- Auto-reconnect on network drops (exponential backoff 200 ms → 5 s, infinite retries)
- Faster reconnect on Wi-Fi roaming / network swap via `ConnectivityManager.NetworkCallback`
- Lifecycle-driven reconnect on app resume (skipped during PiP transitions)
- **Picture-in-Picture** support — playback survives Home / Recents without
  audio interruption
- Proper **AudioFocus** integration (auto-pause on call / notification, ducking,
  Bluetooth routing, Media-volume rocker)
- **Wi-Fi performance lock** while a session is active to suppress radio
  power-save jitter
- **Software-decoder toggle** in settings — escape hatch when a buggy HW
  decoder breaks playback. By default HW decode is used, but
  `c2.exynos.avc.decoder` is automatically excluded on Pixel 8 / 9 to avoid
  the known green-tearing / freeze bug on live MPEG-TS H.264
- Immersive fullscreen, lock mode (long-press to unlock)
- Swipe-to-adjust brightness (left half) and volume (right half)

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
  - `platforms;android-34`
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

- **`SrtDataSource` ↔ ExoPlayer**: SRT live mode delivers fixed-size payloads
  (1316 bytes by default). Media3 extractors call `DataSource.read` with
  arbitrary lengths (down to a few bytes during track sniffing). We absorb
  that mismatch with an internal `rxBuf` in `SrtSocket.kt`: each underlying
  `srt_recv` pulls a full message; the caller is served incrementally.
- **TRANSTYPE first**: `SRTO_TRANSTYPE = SRTT_LIVE` is set before any other
  option per the libsrt configuration guidelines — it bulk-presets
  `MESSAGEAPI`, `TSBPDMODE`, `TLPKTDROP`, and `PAYLOADSIZE`.
- **State race fix**: ExoPlayer's `STATE_IDLE` event from `player.stop()`
  during `disconnect()` is ignored while we're in `CONNECTING` /
  `RECONNECTING`, otherwise it would clobber the new attempt.
- **16 KB alignment**: All native `.so` files are linked with
  `-Wl,-z,max-page-size=16384` (applied via `CMAKE_SHARED_LINKER_FLAGS`
  to both libsrt's and our own JNI library).

## Known limitations / future work

- **No Android TV launcher integration yet** — leanback uses-feature,
  `LEANBACK_LAUNCHER` intent filter, banner, and D-pad-first focus order
  are all pending. Sideloading and using with a phone or touch screen works.
- **No PiP / background audio** when the app is minimised — current behaviour
  drops the SRT socket and reconnects on resume.
- **No stream recording** (writing the received MPEG-TS to disk).
- **No multipath / bonding** on RX (libsrt bonding is compiled in but not used).
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
