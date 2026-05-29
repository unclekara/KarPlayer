package com.karplayer.player

/**
 * One audio track surfaced by the demuxer (one MPEG-TS audio PID, in
 * KarRelay-fed streams). [id] is opaque and stable across the current
 * session; [language] is the ISO-639 code (`rus`, `eng`, …) when the
 * producer set it. The UI uses [label] when present, falling back to
 * [language], then to the id.
 */
data class AudioTrack(
    val id: String,
    val language: String?,
    val label: String?,
    val codec: String?,
    val channels: Int,
    val sampleRate: Int,
    val isSelected: Boolean,
)
