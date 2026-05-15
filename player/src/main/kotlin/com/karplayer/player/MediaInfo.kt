package com.karplayer.player

data class MediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFrameRate: Float = 0f,
    val audioCodec: String? = null,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0
) {
    /** Pixel-aspect-corrected ratio, or 0 if unknown. */
    val aspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else 0f
}
