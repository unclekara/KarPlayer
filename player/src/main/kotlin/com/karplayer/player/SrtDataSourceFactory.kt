package com.karplayer.player

import androidx.media3.datasource.DataSource
import com.karplayer.srt.SrtDataSource
import com.karplayer.srt.SrtOptions

class SrtDataSourceFactory(
    private val options: SrtOptions,
    private val onDataSourceCreated: (SrtDataSource) -> Unit = {}
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val ds = SrtDataSource(options)
        onDataSourceCreated(ds)
        return ds
    }
}
