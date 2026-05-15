package com.karplayer.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

class SrtMediaSourceFactory(
    private val dataSourceFactory: SrtDataSourceFactory
) {
    fun create(uri: Uri): MediaSource {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        return ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
            .createMediaSource(MediaItem.fromUri(uri))
    }
}
