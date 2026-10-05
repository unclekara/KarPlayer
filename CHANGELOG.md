# Changelog

All notable changes to KarPlayer are documented here. Format loosely
follows [Keep a Changelog](https://keepachangelog.com/); the project
uses simple `MAJOR.MINOR` tags.

## [Unreleased]

Nothing yet.

## [0.7] — 2026-10-05

### Changed

- **libsrt moves from 1.5.4 to 1.5.7, with one line patched.** The
  patch is on by default and the modification is disclosed in
  [NOTICE](NOTICE) as MPL-2.0 requires — see below for why it is
  needed at all.

### Added

- **`-PsrtVersion`, `-PabiFilter` and `-PacceptEmptyAckAck`** build
  flags. `-PacceptEmptyAckAck=false` builds against stock libsrt.

  The reason they exist: libsrt 1.5.7 added a check at the top of
  `CUDT::processCtrl` that refuses a control packet with an empty
  payload and returns *before* the dispatch. An ACKACK carries nothing
  but a sequence number in its header, and senders built on gosrt send
  it with no payload, so on 1.5.7 the receiver never runs
  `processCtrlAckAck` and loses the RTT estimate, the NAK interval
  derived from it, the TSBPD drift samples and light ACKs — the drift
  samples being the ones this player's frame-accurate sync leans on.

  The packet is not malformed: draft-sharabayko-srt-01 §3.2.8 says
  plainly that "ACKACK control packets do not contain Control
  Information Field (CIF)". libsrt pads its own for a reason it states
  in a comment beside the code — `writev` will not take a zero-length
  buffer — and in 1.5.7 it began requiring the same of everyone else.
  So the one line this build changes works around a regression
  upstream rather than tolerating a broken peer.

  Measured here, same device, same network, same stream:

  | sender | receiver | RTT |
  |---|---|---|
  | gosrt as released | libsrt 1.5.7 stock | 100 ms, constant |
  | gosrt + padding ([datarhei/gosrt#161](https://github.com/datarhei/gosrt/pull/161)) | libsrt 1.5.7 stock | 4 ms |
  | gosrt as released | libsrt 1.5.7 + `-PacceptEmptyAckAck` | 4 ms |

  Either end fixes it, and 4 ms is what this player reports on 1.5.4
  against the same sender. The sender side is the right fix and
  KarRelay 0.6 ships it; the receiver-side flag is for senders you do
  not control.

## [0.6] — 2026-10-03

### Changed

- **Media3 / ExoPlayer 1.3.1 → 1.11.1.** The old pin was eighteen
  months behind. What this buys the live MPEG-TS path:
  - **Codec no longer swallows all samples** when it was flushed
    before receiving input buffers (1.11.0). Our reconnect loop is a
    stream of `stop()` → `prepare()`, i.e. codec flushes, and in kiosk
    mode it runs unattended and unbounded — this is the single most
    relevant fix in the range.
  - Rendering decision on a new surface fixed so frames aren't dropped
    on devices without placeholder-surface support (1.11.0). We swap
    the surface on `SurfaceView` create/destroy and on PiP transitions.
  - `MediaCodecAudioRenderer` takes the channel mask from the platform
    decoder instead of inferring it from the channel count (1.11.0) —
    more accurate channel readout for multi-track / multi-channel
    sources.
  - MPEG-TS: last frame of a stream reaches the sample queue (1.4.0);
    `IllegalArgumentException` out of `ReorderingBufferQueue` on PES
    packets with no timestamp is fixed (1.9.0) — live encoders emit
    those; last frame also rendered when the final PES has a known
    length (1.11.0).
  - Codec reuse on frame-rate changes below API 30 no longer resets
    the codec where that isn't beneficial (1.10.1) — old TV boxes.
  - Note 1.6.x and 1.7.x are skipped deliberately: a regression
    introduced in 1.6 breaks H.265 SEI parsing
    ([androidx/media#2456](https://github.com/androidx/media/issues/2456)),
    fixed in 1.8.0. We feed HEVC carrying our own `KarSEI-TSYNC` marks.
- **Kotlin 2.0.21 → 2.2.21**, required by Media3 ≥ 1.10 (its
  `kotlin-stdlib` metadata is rejected by a 2.0 compiler). The Compose
  compiler plugin follows the Kotlin version automatically. AGP 8.4.2
  and Gradle 8.7 are unchanged.
- **compileSdk 35 → 36**, required by Media3 ≥ 1.10. `targetSdk` stays
  at **34** on purpose — raising it opts into forced edge-to-edge on
  Android 15, which would fight the immersive fullscreen player. That
  is a separate change with its own UI verification.
- **`LowLatencyRenderersFactory` now builds its renderer through
  `MediaCodecVideoRenderer.Builder`.** Media3 1.5 deprecated every
  positional constructor; the protected Builder-taking constructor is
  the supported path for a subclass. Behaviour is unchanged — the
  `KEY_LOW_LATENCY` opt-in and the `c2.exynos.avc.decoder` carve-out
  for Pixel 8/9 are untouched. Upstream still ships no workaround of
  its own for that decoder, so the carve-out stays.

### Watch out for

- **Track support got stricter in 1.11.0:** tracks with a well-formed
  but *unrecognized* codec profile or level are now reported as
  `supported=NO_EXCEEDS_CAPABILITIES` instead of `supported=YES`. Live
  encoders are not always tidy about profile/level signalling, so a
  source that played on 1.3.1 could in principle be refused. Did *not*
  trigger against vMix (HEVC 1080p + AAC-LATM) on a Pixel 8 — but that
  is one encoder, and other sources still want a check.
- **`MediaCodecAudioRenderer: Audio sink error` is logged once on every
  reconnect** (observed on a Pixel 8, HEVC, hardware decode). Audio
  recovers on its own — the platform `AudioTrack` comes back
  `state:started` and playback continues with no further errors — so
  this is noise rather than breakage. Not established whether 1.3.1
  behaved the same; worth an A/B if it ever turns into audible
  drop-out.
- `android.suppressUnsupportedCompileSdk=36` is set in
  `gradle.properties`: compileSdk 36 is newer than AGP 8.4.2 officially
  knows about. Builds are clean and the APK is correct; drop the flag
  once AGP is bumped.
- Compose BOM is deliberately **not** bumped here. It compiles fine
  against the Kotlin 2.2 compiler, and keeping Material3 behaviour
  frozen means a hardware test of this change exercises playback only,
  not UI regressions. Separate change.
- Changing `compileSdk` or the Kotlin version leaves stale incremental
  state behind. If a build fails with `NullPointerException` in
  `mergeReleaseResources`, or with "Module was compiled with an
  incompatible version of Kotlin … expected version is 2.0.0", stop the
  daemons and delete `*/build/tmp` plus
  `app/build/intermediates/{merged_res,incremental}`. Neither is a real
  incompatibility — a clean build passes.

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
  [KarRelay](https://github.com/unclekara/KarRelay) for which features the relay enforces.

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
