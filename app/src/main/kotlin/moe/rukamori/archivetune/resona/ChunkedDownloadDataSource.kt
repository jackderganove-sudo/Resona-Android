/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Reads a byte range in blocks (round 12). One request for a whole song is
 * throttled by YouTube to about real time (measured on the Pixel: a 60-minute
 * mix at 32–48 KB/s) and long ones break off. Every request asks for at most
 * [chunkSize] bytes; when a block ends, the next starts at the next byte. A
 * failed block (HTTP 403/410 after the link expired, a dropped connection, an
 * early end) is opened again at the same position – never from the start.
 *
 * Pure logic (no Android types) so it can be unit-tested; [ChunkedDownloadDataSource]
 * plugs it into Media3.
 */
class ChunkedReader(
    /** Opens [length] bytes at absolute [position]; returns the bytes available or -1. */
    private val openRange: (position: Long, length: Long) -> Long,
    private val readRange: (buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val closeRange: () -> Unit,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val maxAttempts: Int = 6,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** HTTP 416: asked past the end of the content. */
    private val isPastEnd: (IOException) -> Boolean = { false },
    /** Every failed block: byte position and cause (for the error log). */
    private val onRetry: (Long, IOException) -> Unit = { _, _ -> },
) {
    var position = 0L
        private set
    var requests = 0
        private set
    var retries = 0
        private set
    var finished = false
        private set

    private var end = UNSET
    private var requested = 0L
    private var readInBlock = 0L
    private var opened = false

    /** Starts at [start]; [length] or -1 when unknown. Returns the length or -1. */
    fun open(start: Long, length: Long): Long {
        position = start
        end = if (length != UNSET) start + length else UNSET
        finished = false
        requests = 0
        retries = 0
        openBlock()
        return if (end != UNSET) end - start else UNSET
    }

    private fun openBlock() {
        var length = chunkSize
        if (end != UNSET) length = minOf(length, end - position)
        if (length <= 0) {
            finished = true
            return
        }
        var attempt = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            try {
                requests++
                val available = openRange(position, length)
                opened = true
                requested = if (available != UNSET) minOf(available, length) else length
                readInBlock = 0
                return
            } catch (e: IOException) {
                closeQuietly()
                if (e is InterruptedIOException) throw e
                if (isPastEnd(e)) {
                    finished = true
                    return
                }
                attempt = retryOrThrow(attempt, e)
            }
        }
    }

    private fun retryOrThrow(attempt: Int, e: IOException): Int {
        onRetry(position, e)
        val next = attempt + 1
        if (next >= maxAttempts) throw e
        retries++
        sleep(BACKOFF_MS * (1L shl minOf(attempt, 4)))
        return next
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var attempt = 0
        while (true) {
            if (finished) return END
            val ask = if (requested > 0) (requested - readInBlock).coerceIn(1L, length.toLong()).toInt() else length
            val read =
                try {
                    readRange(buffer, offset, ask)
                } catch (e: IOException) {
                    if (e is InterruptedIOException) throw e
                    // Dropped mid-block: open again at the next missing byte.
                    closeQuietly()
                    attempt = retryOrThrow(attempt, e)
                    openBlock()
                    continue
                }
            if (read == END) {
                closeQuietly()
                when {
                    readInBlock < requested -> {
                        // Shorter than asked: the end of content when the length is unknown,
                        // otherwise a broken response – same position again.
                        if (end == UNSET && readInBlock > 0) {
                            finished = true
                        } else {
                            attempt = retryOrThrow(attempt, IOException("block ended after $readInBlock of $requested bytes"))
                            openBlock()
                        }
                    }
                    end != UNSET && position >= end -> finished = true
                    else -> openBlock()
                }
                continue
            }
            position += read
            readInBlock += read
            return read
        }
    }

    fun close() = closeQuietly()

    private fun closeQuietly() {
        if (!opened) return
        opened = false
        runCatching { closeRange() }
    }

    companion object {
        /** yt-dlp uses 10 MB for YouTube; 8 MB stays below the throttling with few requests. */
        const val DEFAULT_CHUNK_SIZE = 8L * 1024 * 1024
        const val UNSET = -1L
        const val END = -1
        private const val BACKOFF_MS = 500L
    }
}

/**
 * Media3 wrapper: [upstream] resolves the media id to the current stream URL on
 * every open and invalidates it on failure, so a retried block gets a fresh link.
 */
class ChunkedDownloadDataSource(
    private val upstream: DataSource,
    chunkSize: Long = ChunkedReader.DEFAULT_CHUNK_SIZE,
    private val report: (key: String?, bytes: Long, seconds: Double, requests: Int, retries: Int) -> Unit = { _, _, _, _, _ -> },
    onRetry: (key: String?, position: Long, IOException) -> Unit = { _, _, _ -> },
) : DataSource {
    private var base: DataSpec? = null
    private var startedAt = 0L
    private var startPosition = 0L

    private val reader =
        ChunkedReader(
            openRange = { position, length -> upstream.open(base!!.subrange(position - base!!.position, length)) },
            readRange = { buffer, offset, length -> upstream.read(buffer, offset, length) },
            closeRange = { upstream.close() },
            chunkSize = chunkSize,
            isPastEnd = { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 416 },
            onRetry = { position, e -> onRetry(base?.key, position, e) },
        )

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        base = dataSpec
        startedAt = System.nanoTime()
        startPosition = dataSpec.position
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) ChunkedReader.UNSET else dataSpec.length
        return reader.open(dataSpec.position, length).let { if (it == ChunkedReader.UNSET) C.LENGTH_UNSET.toLong() else it }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length).let { if (it == ChunkedReader.END) C.RESULT_END_OF_INPUT else it }

    override fun getUri(): Uri? = upstream.uri ?: base?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        base?.let { report(it.key, reader.position - startPosition, (System.nanoTime() - startedAt) / 1e9, reader.requests, reader.retries) }
        reader.close()
    }
}
