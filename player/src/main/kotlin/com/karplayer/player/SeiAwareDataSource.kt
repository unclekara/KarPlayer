package com.karplayer.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Decorator that peeks at the byte stream flowing through an inner
 * [DataSource], detects KarRelay SEI timestamps via [SeiTimecodeParser], and
 * forwards each found timestamp through [onTimestamp] without disturbing
 * the bytes themselves.
 *
 * Only used when the user picked SyncMode.SEI_SYNC — otherwise the inner
 * data source is passed straight to ExoPlayer.
 */
internal class SeiAwareDataSource(
    private val inner: DataSource,
    private val parser: SeiTimecodeParser,
    private val onTimestamp: (Long) -> Unit
) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        parser.reset()
        return inner.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = inner.read(buffer, offset, length)
        if (n > 0) {
            val found = parser.feed(buffer, offset, n)
            if (found.isNotEmpty()) {
                for (ts in found) onTimestamp(ts)
            }
        }
        return n
    }

    override fun addTransferListener(transferListener: TransferListener) {
        inner.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): MutableMap<String, MutableList<String>> =
        inner.responseHeaders

    override fun close() {
        parser.reset()
        inner.close()
    }
}
