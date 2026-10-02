# Changelog

All notable changes to KarPlayer are documented here. Format loosely
follows [Keep a Changelog](https://keepachangelog.com/); the project
uses simple `MAJOR.MINOR` tags.

## [0.5] — 2026-10-02

### Added

- **Kiosk mode** — opt-in switch in Settings, for unattended screens
  (signage, monitor walls, venue displays). Persisted as `kiosk_mode`
  in `ConnectionConfig`.
  - **No chrome at all** during playback: stats overlay, SEI-sync
    indicator, bottom bar, connecting/reconnecting spinner with its
    attempt counter, and the error panel are all gated off. Tap /
    D-pad-CENTER no longer summons the overlay, and the brightness /
    volume swipe HUD is disabled (the volume rocker still works — that
    one is the system's).
  - **Black while off air.** A `SurfaceView` holds its last decoded
    frame after the player stops, so a dropped signal used to freeze on
    a stale image. Kiosk mode covers the video layer with opaque black
    once playback stops, after a 1 s grace period so ordinary
    sub-second rebuffers don't flash black.
  - **Unbounded reconnect.** `PlayerManager` ignores
    `MAX_RECONNECT_ATTEMPTS` for kiosk sessions: it never emits
    `exitToMenu`, so the player waits on black indefinitely and picks
    the stream back up on its own when the sender returns. Non-kiosk
    sessions keep the 3-attempt bound.
  - **Double-BACK to leave.** With no visible chrome, a single stray
    BACK on a remote must not drop an unattended screen out of
    playback. Two presses within 2.5 s exit; the first shows a brief
    "Press BACK again to exit" hint — the only pixel kiosk mode draws
    that isn't video.

  - **Autostart** (`kiosk_autostart`, second switch, shown only while
    kiosk mode is on). Skips the menu on launch and connects straight
    into the stream, so the screen recovers on its own after a power
    cycle or an app restart. Fires once per process from
    `KarPlayerNavRoot` (guarded by a `rememberSaveable` flag so a
    rotation or a deliberate double-BACK exit doesn't re-trigger it),
    and only when `ConnectionConfig.isConnectable` holds — no user is
    around to fix a half-filled form. This is autostart of the *stream
    when the app opens*, not of the *app when the device boots*; the
    latter belongs to the launcher / MDM.

### Fixed

- **Settings screen no longer resets fields it doesn't edit.**
  `ConnectionScreen` built a fresh `ConnectionConfig` on Connect, which
  silently dropped `preferredAudioLanguage` — so pressing Connect in
  Settings threw away the audio-track choice persisted in 0.4. It now
  builds via `initial.copy(...)`, which also keeps future non-form
  fields safe.
- **API.md** persisted-config table: `relay_http_port` default was still
  documented as 8080 (changed to 8484 in 0.3), and `preferred_audio_lang`
  (added in 0.4) was missing.

## [0.4] — 2026-09

### Added

- **F3 — Multi-track audio selection** (KarRelay ROADMAP). The
  player now enumerates all audio tracks the MPEG-TS demuxer
  surfaces in `onTracksChanged` (not just the active one) and
  exposes them as `PlayerManager.audioTracks: StateFlow<List<AudioTrack>>`
  + `selectedAudioTrackId: StateFlow<String?>`.
  - New `AudioTrack(id, language, label, codec, channels,
    sampleRate, isSelected)` data class. `id` is a session-scoped
    `"groupIdx-trackIdx"` token; the persisted user choice is
    the ISO-639 `language` code.
  - `PlayerManager.selectAudio(trackId)` applies a
    `TrackSelectionOverride` via
    `player.trackSelectionParameters.setOverrideForType(...)`.
    Switching is seamless — ExoPlayer's selector swaps the audio
    renderer's input without restarting the SRT connection.
    `setPreferredAudioLanguage` is mirrored so the choice
    survives a player rebuild.
  - `PlayerScreen` BottomBar shows a **MusicNote** icon only when
    ≥ 2 audio tracks are surfaced. Tap opens a bottom-anchored
    `AudioTrackPicker` (pure Compose, no Material3 ModalBottomSheet
    — better D-pad behaviour on TV). Each row shows label,
    language, codec, channels.
  - `ConnectionConfig.preferredAudioLanguage` persists via
    `KEY_PREFERRED_AUDIO_LANG` in SharedPreferences. Applied at
    connect time so reconnects open on the same language.
  - `PlayerViewModel.selectAudio(trackId, context)` zeroes the
    boilerplate: persists the resolved language to
    `ConnectionConfig` so the next session inherits.

### Changed

- **SEI sync `+ / −` steppers always step by 1.** Previously
  "Target lag, ms" stepped by 10 and "Deadband, ms" by 5, while
  "Max speed adjust, %" already stepped by 1. The slider next to
  each field still covers the full range; the buttons are now
  for 1-unit fine tuning.
- **Player overlay icons** (Lock / Fullscreen / Audio) gained a
  1 dp dark drop shadow behind a pure-white foreground —
  industry-standard YouTube/Netflix-style "halo" treatment so
  glyphs stay readable on any video frame (dark studio, white
  sky, mid-tone newsroom). Implemented via the new
  `ShadowedIcon` composable in `PlayerScreen.kt`. HudBar
  (volume / brightness) already had its own dark-pill backdrop,
  unchanged.
- **Stats overlay** Audio row now appends `× N tracks` when more
  than one audio track is surfaced — quick diagnostic for "vMix
  is in multi-channel mode vs multiple-tracks mode" confusion
  without having to run `adb logcat`.

### Removed

- **Bouncing-DVD `KarPlayer SRT` watermark** is gone entirely.
  Originally a second-line-of-defence display on `SEI_SYNC`
  sessions; the player-side LCNS-SEI gate was dropped earlier
  (no real protection against patched binaries — see KarRelay
  LICENSING.md "deliberately not gated" section), and the
  watermark itself had nothing left to enforce. Code path
  surgically excised: `_watermarkVisible` StateFlow,
  `watermarkJob` timer, the 15-minute cooldown constant, the
  `BouncingWatermark` composable with its withFrameNanos
  animation, and the SMPTE-bar palette. Zero remaining matches
  for `watermark` in the player source tree.

### Fixed

- Audio-tracks logging in `PlayerManager.onTracksChanged` now
  dumps every surfaced track with codec / language / channels
  to logcat. Helps diagnose "I sent 4 audio tracks and only one
  plays" — the most common cause is vMix in *multi-channel* mode
  (one 8-channel PID) vs *multiple audio tracks* mode (N
  separate PIDs), and only the latter surfaces N distinct
  tracks to the picker.

## [0.3] — unreleased

### Added
- **SEI timecode sync (`SEI_SYNC` mode).** Parses a `KarSEI-TSYNC` SEI NAL
  injected by KarRelay and steers playback speed (`PlayerSyncController`) to
  hold a frame-accurate target lag. Tunables in the UI: target lag, deadband,
  max speed adjust.
- **Relay clock sync.** Lightweight NTP-style HTTP probe to the relay's
  `/api/time` (`RelayClockSync`), picks the lowest-RTT sample, aligns
  phone↔relay clocks before SEI sync engages.
- **Low-latency mode.** Playback-speed nudge (1.03×/1.06×) to chase the live
  edge without `seekTo()` (which would reset the SRT loader).
- **Connection-rejection reasons.** The player now shows *why* a session was
  refused or dropped:
  - Wrong stream id → reads SRT `SRT_REJX_FORBIDDEN` via
    `srt_getrejectreason()` (native), surfaces "Wrong stream ID".
  - Viewer limit (relay free tier) → `SRT_REJX_OVERLOAD` → "max 3 viewers".
  - Operator kick / mid-session drop → queried over HTTP
    (`/api/disconnect-reason`) since SRT carries no close reason.
- **Bounded reconnect.** Up to 3 attempts (was infinite); on exhaustion the
  player returns to the connection menu and shows the reason briefly
  (auto-dismissed after 8 s so the app never looks "limited").
- **Configurable relay service port** in the connection form (default 8484),
  persisted; used for clock sync and the disconnect-reason channel.

### Changed
- Default relay service port 8080 → **8484**.
- Reconnect success is now defined as reaching `PLAYING` (not `BUFFERING`),
  fixing a counter-reset bug where every attempt looked successful.

### Deprecated
- **Bouncing-DVD watermark on SEI_SYNC sessions.** Originally driven by
  the dropped LCNS-SEI scheme: it was meant to appear at 15 min if the
  relay never broadcast a valid licence token. With LCNS removed (the
  whole player-side licensing chain was dropped — see Removed below),
  the watermark code path (`watermarkVisible`, `BouncingWatermark`,
  `WATERMARK_DELAY_MS`) no longer serves a purpose. The visibility flow
  exists in code but is intentionally **not** documented as a current
  feature; slated for removal in a follow-up.

### Removed
- **Player-side licensing chain.** No Ed25519 verify, no LCNS-SEI
  parsing, no licensing client. Pro features are enforced entirely
  inside KarRelay; the player only reflects rejection reasons it
  receives over SRT and the HTTP side-channel. See
  [../KarRelay/LICENSING.md](../KarRelay/LICENSING.md) for the rationale.

## [0.2.1] — 2026-05

### Changed
- TV-friendly connection form: no IME spam on focus, focusable decoder switch,
  D-pad-first navigation.

## [0.2] — 2026-05

### Added
- **Listener** and **Rendezvous** SRT modes (in addition to Caller).
- Android TV / Leanback support (launcher, banner, D-pad focus).
- **AudioFocus** integration (auto-pause on call, ducking, BT routing).
- **Wi-Fi performance lock** during a session.
- **NetworkCallback**-driven fast reconnect on Wi-Fi roaming.
- **Picture-in-Picture** — playback survives Home/Recents.
- **Software-decoder toggle**; auto-exclude `c2.exynos.avc.decoder` on
  Pixel 8/9 (green-tearing/freeze bug on live H.264).
- Low-latency receiver tuning.

## [0.1] — 2026-05

### Added
- Initial Android SRT receiver: libsrt 1.5.4 + mbedtls 3.6.2 (built from
  source per ABI), Media3/ExoPlayer MPEG-TS pipeline, Compose UI.
- Caller mode, AES passphrase, adjustable latency, live stats overlay,
  auto-reconnect, immersive fullscreen.
