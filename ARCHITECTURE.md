# KarPlayer — architecture

Low-latency Android SRT receiver. Four Gradle modules, one native library,
a Compose UI, and a Media3 playback core. This document explains how they
fit together and where the non-obvious decisions live.

```
┌──────────────────────────────────────────────────────────────┐
│ app/        MainActivity, DI, launcher icon, PiP plumbing      │
├──────────────────────────────────────────────────────────────┤
│ ui/         Compose screens + PlayerViewModel                  │
│   KarPlayerNavRoot → QuickConnect / ConnectionScreen / Player  │
├──────────────────────────────────────────────────────────────┤
│ player/     PlayerManager (ExoPlayer), reconnect, SEI sync,    │
│             clock sync, SrtDataSource bridge                   │
├──────────────────────────────────────────────────────────────┤
│ srt/        JNI bridge (srt_jni.cpp) + libsrt 1.5.4 + mbedtls  │
│             SrtSocket / SrtNative / SrtDataSource              │
└──────────────────────────────────────────────────────────────┘
```

---

## Module responsibilities

### `srt/` — transport
- **`srt_jni.cpp`** — thin JNI over libsrt. Create/connect/read/close/stats,
  plus `nativeLastRejectReason()` exposing `srt_getrejectreason()`.
  `SRTO_TRANSTYPE = SRTT_LIVE` is set first (presets message-API, TSBPD,
  pkt-drop, 1316-byte payload). 16 KB-page-aligned `.so` for Android 15+.
- **`SrtNative`** — `external fun` declarations; reject-reason constants.
- **`SrtSocket`** — Kotlin wrapper. Absorbs the live-mode fixed-payload vs
  arbitrary-read mismatch via an internal `rxBuf`. Throws
  **`SrtConnectException(rejectReason)`** on handshake refusal.
- **`SrtDataSource` / `SrtDataSourceFactory`** — Media3 `DataSource` backed by
  an `SrtSocket`; rejection exceptions propagate as the `IOException` cause.

### `player/` — playback core
- **`PlayerManager`** — the single source of truth. Owns the `ExoPlayer`,
  the connect/reconnect lifecycle, and all the `StateFlow`s the UI
  observes (`playerState`, `stats`, `lastError`, `mediaInfo`,
  `audioTracks`, `selectedAudioTrackId`, `reconnectAttempt`,
  `syncState`, …) plus an `exitToMenu` `SharedFlow`.
- **`AudioTrack`** (added in 0.4) — one demuxed audio rendition
  (one MPEG-TS audio PID for KarRelay-fed streams). Surfaces
  `id`, `language`, `label`, `codec`, `channels`, `sampleRate`,
  `isSelected`. `PlayerManager.selectAudio(trackId)` applies an
  ExoPlayer `TrackSelectionOverride` and mirrors the choice to
  `setPreferredAudioLanguage` so a player rebuild (buffer-ceiling
  change) doesn't drop it.
- **`PlayerSyncController`** — SEI-driven speed control. Computes
  `lag = (now − sei_micros) − clockOffset + (buffered − currentPosition)`
  and nudges `PlaybackParameters` to hold the target.
- **`RelayClockSync`** — HTTP helpers: `/api/time` clock estimation and the
  `/api/disconnect-reason` side-channel.
- **`SeiTimecodeParser` / `SeiAwareDataSource`** — scan the TS for the
  `KarSEI-TSYNC` UUID with a carry buffer across reads.
- **`LowLatencyRenderersFactory`** — codec selection + `KEY_LOW_LATENCY`,
  with the Exynos AVC carve-out.

### `ui/` — Compose
- **`KarPlayerNavRoot`** — three screens: `QUICK`, `SETTINGS`
  (`ConnectionScreen`), `PLAYER`. Holds the persisted `ConnectionConfig`.
  Surfaces `lastError` briefly on the menu, auto-dismissing it after 8 s.
- **`PlayerScreen`** — video surface, HUD, error UI; observes
  `exitToMenu` to return to the menu after reconnects are exhausted.
- **`ConnectionScreen` / `QuickConnectScreen`** — full / one-tap connect.
- **`PlayerViewModel`** — thin pass-through to `PlayerManager`.

### `app/`
- `MainActivity`, dependency wiring, launcher icon, PiP entry/exit, the
  `recentlyInPip` guard that prevents a reconnect storm on PiP transitions.

---

## Key flows

### Connect
`ConnectionConfig` → `PlayerViewModel.connect` → `PlayerManager.connect`:
rebuild ExoPlayer if the buffer ceiling changed → create sync controller if
`SEI_SYNC` → (optionally clock-sync first) → `startSession` builds an
`SrtMediaSource` over `SrtDataSource` and `prepare()`s.

### Reconnect (bounded)
On `onPlayerError`: refine the message (SRT reject code if present), then run
the reconnect loop — **max 3 attempts** with exponential backoff. An attempt
*succeeds* only when ExoPlayer reaches `PLAYING`; `BUFFERING` is "still
trying". A bumped `errorEpoch` (set in `onPlayerError`) lets instant
rejections fail fast instead of waiting the per-attempt timeout. After 3
failures → `exitToMenu` with the reason.

### Rejection reasons
SRT carries a numeric handshake-rejection code (same values in gosrt and
libsrt: `REJX_FORBIDDEN=1403`, `REJX_OVERLOAD=1402`). The native layer stores
it; `SrtConnectException` exposes `isWrongStreamId` / `isViewerLimit`.
Mid-session kicks carry no SRT reason, so the player asks the relay over HTTP
(`/api/disconnect-reason?streamid=…`).

### SEI sync
Relay injects `KarSEI-TSYNC` (UTC µs) per I-frame → player parses → controller
holds target lag via small speed adjustments. Requires the relay to be on a
Pro licence (`sei_sync`); otherwise the relay emits no TSYNC and the player
falls back to LOW_LATENCY / OFF.

---

## Cross-cutting decisions

- **State-race fix**: ExoPlayer's `STATE_IDLE` from `stop()` during
  `disconnect()` is ignored while `CONNECTING`/`RECONNECTING`.
- **No `seekTo()` for live edge**: a seek on a `ProgressiveMediaSource` over
  SRT forces a loader reset → a fresh SRT connection. We use speed nudges.
- **16 KB alignment**: all native `.so` linked with
  `-Wl,-z,max-page-size=16384`.
- **Licensing**: KarPlayer is **not** part of the licensing chain — no
  Ed25519 verify, no activation client. What it shows (sync availability,
  rejection reasons) is driven entirely by what the relay sends.

See [API.md](API.md) for the wire-protocol and module contracts,
[README.md](README.md) for build/run.
