/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as the iPad's PlayerStylesTests.testCodecLine. */
class CodecLineTest {
    @Test
    fun codecNames() {
        assertEquals("Opus", ResonaCodecLine.codecName(null, "audio/webm; codecs=\"opus\""))
        assertEquals("AAC", ResonaCodecLine.codecName("mp4a.40.2", "audio/mp4"))
        assertEquals("HE-AAC", ResonaCodecLine.codecName("mp4a.40.5", "audio/mp4"))
        assertNull(ResonaCodecLine.codecName("", "audio/mp4"))
    }

    @Test
    fun labels() {
        assertEquals("Opus · WEBM", ResonaCodecLine.label("opus", "audio/webm; codecs=\"opus\""))
        assertEquals("MP4", ResonaCodecLine.label("", "audio/mp4"))
        assertEquals("AAC · MP4", ResonaCodecLine.label("mp4a.40.2", "audio/mp4"))
    }
}
