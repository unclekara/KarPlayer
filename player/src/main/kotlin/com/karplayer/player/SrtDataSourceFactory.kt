package com.karplayer.player

import androidx.media3.datasource.DataSource
import com.karplayer.srt.SrtDataSource
import com.karplayer.srt.SrtOptions

/**
 * @param onSeiTimestamp If non-null, every created data source is wrapped
 *     in a [SeiAwareDataSource] that scans the byte stream for KarRelay's
 *     `KarSEI-TSYNC` markers and routes the extracted UTC-µs timestamp
 *     to this callback. ExoPlayer sees the unchanged bytes either way.
 */
class SrtDataSourceFactory(
    private val options: SrtOptions,
    private val onDataSourceCreated: (SrtDataSource) -> Unit = {},
    private val onSeiTimestamp: ((Long) -> Unit)? = null
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val ds = SrtDataSource(options)
        onDataSourceCreated(ds)
        val seiSink = onSeiTimestamp ?: return ds
        return SeiAwareDataSource(ds, SeiTimecodeParser(), seiSink)
    }
}
