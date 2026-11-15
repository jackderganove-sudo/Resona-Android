/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Block download against a fake "server" that honours ranges like YouTube,
 * can refuse an open (403 = expired link), drop the connection mid-block or
 * answer 416 past the end.
 */
class ChunkedDownloadDataSourceTest {
    private val content = ByteArray(2_500_000) { (it * 31 + 7).toByte() }

    private class PastEnd : IOException("416")
    private class Forbidden : IOException("403")

    private inner class FakeServer(
        val refuseOpens: Set<Int> = emptySet(),
        val dropAfter: Map<Int, Int> = emptyMap(),
        /** Like YouTube on the Pixel: past the end an empty body instead of 416. */
        val emptyPastEnd: Boolean = false,
    ) {
        val ranges = ArrayList<Pair<Long, Long>>()
        private var pos = 0L
        private var left = 0L
        private var opens = 0
        private var dropIn = -1

        fun open(position: Long, length: Long): Long {
            val n = opens++
            if (n in refuseOpens) throw Forbidden()
            if (position >= content.size) {
                if (!emptyPastEnd) throw PastEnd()
                left = 0
                return length // claims the bytes, sends none
            }
            ranges += position to length
            pos = position
            left = if (length < 0) content.size - pos else minOf(length, content.size - pos)
            dropIn = dropAfter[n] ?: -1
            return left
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return -1
            var n = minOf(length.toLong(), left, 50_000L).toInt()
            if (dropIn >= 0) {
                if (dropIn == 0) throw IOException("connection reset")
                n = minOf(n, dropIn)
                dropIn -= n
            }
            System.arraycopy(content, pos.toInt(), buffer, offset, n)
            pos += n
            left -= n
            return n
        }
    }

    private fun reader(server: FakeServer, maxAttempts: Int = 6, total: Long = -1, onRetry: (Long, IOException) -> Unit = { _, _ -> }) =
        ChunkedReader(server::open, server::read, {}, chunkSize = 1_000_000, maxAttempts = maxAttempts, sleep = {}, isPastEnd = { it is PastEnd }, onRetry = onRetry, discoverTotal = { total })

    private fun readAll(r: ChunkedReader, start: Long = 0, length: Long = -1): ByteArray {
        r.open(start, length)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = r.read(buffer, 0, buffer.size)
            if (n == -1) break
            out.write(buffer, 0, n)
        }
        r.close()
        return out.toByteArray()
    }

    @Test
    fun downloadsInBlocksOfTheChunkSize() {
        val server = FakeServer()
        assertArrayEquals(content, readAll(reader(server)))
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), server.ranges.map { it.first })
        assertTrue(server.ranges.all { it.second == 1_000_000L })
    }

    @Test
    fun knownLengthStopsWithoutAskingPastTheEnd() {
        val server = FakeServer()
        assertArrayEquals(content, readAll(reader(server), 0, content.size.toLong()))
        assertEquals(500_000L, server.ranges.last().second)
        assertEquals(3, server.ranges.size)
    }

    @Test
    fun expiredLinkIsRetriedAtTheSamePosition() {
        val server = FakeServer(refuseOpens = setOf(1))
        val positions = ArrayList<Long>()
        assertArrayEquals(content, readAll(reader(server, onRetry = { pos, _ -> positions += pos })))
        assertEquals(listOf(1_000_000L), positions)
    }

    @Test
    fun droppedConnectionContinuesWhereItStopped() {
        val server = FakeServer(dropAfter = mapOf(0 to 300_000))
        assertArrayEquals(content, readAll(reader(server)))
        assertEquals(300_000L, server.ranges[1].first)
    }

    @Test
    fun resumesFromAPositionInsideTheFile() {
        val server = FakeServer()
        assertArrayEquals(content.copyOfRange(1_200_000, content.size), readAll(reader(server), 1_200_000))
        assertEquals(1_200_000L, server.ranges.first().first)
    }

    @Test(expected = IOException::class)
    fun givesUpAfterMaxAttempts() {
        readAll(reader(FakeServer(refuseOpens = (0..10).toSet()), maxAttempts = 3))
    }

    @Test
    fun longMixInManyBlocks() {
        // 60 min at 128 kbit/s ≈ 58 MB → 7 blocks of 8 MB; here scaled down 1:25.
        val server = FakeServer()
        val r = reader(server)
        readAll(r)
        assertEquals(3, r.requests - 1) // three ranges plus the 416 probe at the end
        assertTrue(r.finished)
    }

    @Test
    fun knownTotalFromTheResponseStopsAtTheEndWithoutProbing() {
        // Seen on the Pixel: 60.1 MB loaded, then the probe past the end got an empty body (EOF) forever.
        val server = FakeServer(emptyPastEnd = true)
        val retries = ArrayList<Long>()
        val r = reader(server, total = content.size.toLong(), onRetry = { pos, _ -> retries += pos })
        assertEquals(content.size.toLong(), r.open(0, -1))
        r.close()
        assertArrayEquals(content, readAll(reader(FakeServer(emptyPastEnd = true), total = content.size.toLong(), onRetry = { pos, _ -> retries += pos })))
        assertTrue("no retries at the end", retries.isEmpty())
    }

    @Test
    fun totalFromContentRangeHeader() {
        assertEquals(63_031_296L, totalFromHeaders(mapOf("content-range" to listOf("bytes 0-8388607/63031296"))))
        assertEquals(null, totalFromHeaders(mapOf("Content-Range" to listOf("bytes 0-8388607/*"))))
        assertEquals(null, totalFromHeaders(emptyMap()))
    }
}
