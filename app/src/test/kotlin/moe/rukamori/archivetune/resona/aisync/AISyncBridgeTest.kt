/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glue around whisper.cpp: bridge output, LRC output, resampling, input lines. Placeholder text only. */
class AISyncBridgeTest {
    @Test
    fun parsesBridgeOutputAndDropsNonWords() {
        val tsv = "Platz\t1.00\t1.40\n[Musik]\t2\t3\n halter\t1.5\t1.5\n(Applaus)\t4\t5\nkaputt\n"
        val words = WhisperNative.parse(tsv)
        assertEquals(listOf("Platz", "halter"), words.map { it.text })
        assertEquals(1.0, words[0].start, 0.001)
        // Zero-length words get a minimal duration.
        assertEquals(1.55, words[1].end, 0.001)
    }

    @Test
    fun serializesEnhancedLrc() {
        val lines = listOf(
            AlignedLine("Platzhalter eins", 1.0, listOf(AlignedWord("Platzhalter ", 1.0, 1.4), AlignedWord("eins", 1.5, 1.9))),
            AlignedLine("Zeile ohne Wörter", 65.25),
            AlignedLine("ohne Zeit", null),
        )
        assertEquals(
            "[00:01.00]<00:01.00>Platzhalter <00:01.50>eins\n[01:05.25]Zeile ohne Wörter",
            Lrc.serialize(lines),
        )
    }

    @Test
    fun resamplesTo16k() {
        val input = FloatArray(48_000) { (it % 100) / 100f }
        val out = CachedSongAudio.resample(input, 48_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(input[300], out[100], 0.0001f)
        assertTrue(CachedSongAudio.resample(input, 16_000, 16_000) === input)
    }

    @Test
    fun syncedInputFromLrc() {
        val lrc = "[00:01.00]Platzhalterzeile eins\n[00:04.50]Platzhalterzeile zwei\n[00:08.00]Platzhalterzeile drei"
        val synced = AISyncInput.synced(lrc)
        assertEquals(3, synced.size)
        assertEquals(4.5, synced[1].startTime!!, 0.001)
        assertEquals(listOf("Platzhalterzeile eins", "Platzhalterzeile zwei", "Platzhalterzeile drei"), AISyncInput.lines(lrc))
        assertTrue(AISyncInput.synced("Platzhalter ohne Zeiten\nzweite Zeile").isEmpty())
    }

    @Test
    fun modelUrlsPointAtWhisperCppRepo() {
        WhisperModel.entries.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-"))
        }
    }
}
