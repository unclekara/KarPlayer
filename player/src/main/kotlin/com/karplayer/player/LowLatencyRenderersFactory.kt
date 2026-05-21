package com.karplayer.player

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * RenderersFactory that injects [MediaFormat.KEY_LOW_LATENCY] = 1 into the
 * video decoder configuration. HW HEVC/AVC decoders on Android default to
 * 3–4 frames of reordering / parallel-decode buffer (~100–130 ms at 30 fps);
 * the low-latency flag tells the codec to disable that for the live use case.
 *
 * Requires Android 11 (API 30). On older devices the flag is a no-op and
 * playback falls back to the device's default latency.
 *
 * Also sets `allowedJoiningTimeMs = 0` so the renderer doesn't wait for the
 * next keyframe before resuming playback after a flush — useful for our
 * forced reconnect-on-resume path.
 */
class LowLatencyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                /* allowedJoiningTimeMs = */ 0L,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                /* maxDroppedFramesToNotify = */ 50
            ) {
                override fun getMediaFormat(
                    format: Format,
                    codecMimeType: String,
                    codecMaxValues: CodecMaxValues,
                    codecOperatingRate: Float,
                    deviceNeedsNoPostProcessWorkaround: Boolean,
                    tunnelingAudioSessionId: Int
                ): MediaFormat {
                    val mf = super.getMediaFormat(
                        format, codecMimeType, codecMaxValues, codecOperatingRate,
                        deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        mf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    return mf
                }
            }
        )
    }
}
