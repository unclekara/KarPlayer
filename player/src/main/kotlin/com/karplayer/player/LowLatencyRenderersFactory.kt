package com.karplayer.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * RenderersFactory that opts into [MediaFormat.KEY_LOW_LATENCY] = 1 on
 * decoders that advertise [MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency]
 * (Android 11+). HW HEVC/AVC decoders default to 3–4 frames of reordering
 * buffer (~100–130 ms at 30 fps); the low-latency flag tells the codec to
 * disable that for the live use case.
 *
 * Caveats baked in:
 * - On API < 30 the flag does not exist; we leave the codec in default mode.
 * - We *always* skip AVC even when its decoder claims to support the
 *   feature. The Exynos `c2.exynos.avc.decoder` on Pixel 8 advertises
 *   FEATURE_LowLatency but freezes / drops frames when the flag is set.
 *   The latency win on AVC is small enough that the conservative choice is
 *   the right one until we can identify well-behaving AVC decoders.
 * - On codecs that don't advertise the feature (older Amlogic STB, etc.)
 *   the flag is also skipped — it would be undefined behaviour to set it.
 *
 * We do *not* override `allowedJoiningTimeMs`: the default 5 s gives the
 * renderer enough breathing room after a flush (reconnect, seek, surface
 * swap) to actually decode the next keyframe before pushing pixels, which
 * matters a lot for slower AVC HW paths.
 */
/**
 * @param forceSoftwareDecoder Called on every codec selection. When it
 *     returns true the selector restricts the candidate list to
 *     software-only decoders — the user-facing "Use software decoder"
 *     escape hatch for HW-decoder bugs.
 */
class LowLatencyRenderersFactory(
    context: Context,
    private val forceSoftwareDecoder: () -> Boolean = { false }
) : DefaultRenderersFactory(context) {

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
        // Codec selection rules:
        // - When the user has flipped "Use software decoder" on, hand back
        //   only software-only candidates (regardless of MIME). ExoPlayer
        //   falls back to a software decoder for everything.
        // - Otherwise apply the AVC quirk: drop `c2.exynos.avc.decoder` on
        //   Pixel 8 / Pixel 9 — it produces green tearing and freezes on
        //   live MPEG-TS H.264. ExoPlayer falls back to the next preferred
        //   decoder (usually Google's software AVC), which handles 1080p30
        //   comfortably on these SoCs.
        val avcSafeSelector = MediaCodecSelector { mime, secure, tunneling ->
            val defaults = mediaCodecSelector.getDecoderInfos(mime, secure, tunneling)
            if (forceSoftwareDecoder()) {
                val sw = defaults.filter { it.softwareOnly }
                return@MediaCodecSelector if (sw.isNotEmpty()) sw else defaults
            }
            if (!MimeTypes.VIDEO_H264.equals(mime, ignoreCase = true)) return@MediaCodecSelector defaults
            val filtered = defaults.filterNot { info ->
                val n = info.name.lowercase()
                n.contains("exynos") && (n.contains("avc") || n.contains("h264"))
            }
            if (filtered.isNotEmpty()) filtered else defaults
        }

        out.add(
            object : MediaCodecVideoRenderer(
                context,
                avcSafeSelector,
                allowedVideoJoiningTimeMs,
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
                    if (shouldSetLowLatency(codecMimeType)) {
                        mf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    return mf
                }
            }
        )
    }

    private fun shouldSetLowLatency(codecMimeType: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        // Defensive: Exynos AVC decoder on Pixel 8 declares the feature but
        // misbehaves with it set, so we never enable on H.264 regardless of
        // what the device advertises.
        if (MimeTypes.VIDEO_H264.equals(codecMimeType, ignoreCase = true)) return false
        return codecAdvertisesLowLatency(codecMimeType)
    }

    private fun codecAdvertisesLowLatency(mime: String): Boolean = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info ->
            if (info.isEncoder) return@any false
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            runCatching {
                info.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        }
    }.getOrDefault(false)
}
