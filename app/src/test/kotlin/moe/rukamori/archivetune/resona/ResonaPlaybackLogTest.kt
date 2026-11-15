/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import moe.rukamori.archivetune.playback.PlaybackStreamRecoveryTrackerAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Same cases as the iPad's StreamClientFallbackTests. Invented ids, no credentials. */
class ResonaPlaybackLogTest {
    @Test
    fun redactionKeepsHostAndItagOnly() {
        val raw = "load failed https://rr3---sn-invented.googlevideo.com/videoplayback?expire=1&itag=140&sig=AAAAABBBBBCCCCCDDDDDEEEEEFFFFFGGGGG&pot=xyz " +
            "cookie=SAPISID123 user someone@example.com token: abcdef visitorData=Cgt0ZXN0dmlzaXRvcg%3D%3D " + "Z".repeat(40)
        val clean = PlaybackLogRedaction.redact(raw)
        assertTrue(clean, clean.contains("rr3---sn-invented.googlevideo.com itag=140"))
        assertFalse(clean.contains("sig="))
        assertFalse(clean.contains("SAPISID123"))
        assertFalse(clean.contains("someone@example.com"))
        assertFalse(clean.contains("abcdef"))
        assertFalse(clean.contains("Cgt0ZXN0"))
        assertFalse(clean.contains("Z".repeat(40)))
        assertTrue(clean.contains("load failed"))
    }

    @Test
    fun statusFromText() {
        assertEquals(403, PlaybackLogRedaction.httpStatus("Response code: 403"))
        assertEquals(null, PlaybackLogRedaction.httpStatus("all fine 200"))
    }

    @Test
    fun keepsTheLast200AndExports() {
        repeat(205) { i -> ResonaPlaybackLog.record("vid$i", "WEB_REMIX", "resolve", 403, "invented $i", time = i * 1000L) }
        val kept = ResonaPlaybackLog.all()
        assertEquals(200, kept.size)
        assertEquals("vid5", kept.first().videoId)
        assertTrue(kept.last().line(ZoneOffset.UTC).endsWith("vid204  WEB_REMIX  resolve  HTTP 403  invented 204"))
        assertTrue(ResonaPlaybackLog.exportText("1.0.13", "Test").startsWith("Resona 1.0.13 · Test"))
    }

    @Test
    fun severalRetriesPerSongAndRetryResets() {
        val tracker = PlaybackStreamRecoveryTrackerAccess.create(maxAttempts = 3)
        assertTrue((1..3).all { tracker("a") })
        assertFalse("refused clients beyond the limit end the retries", tracker("a"))
        assertTrue("another song starts fresh", tracker("b"))
        PlaybackStreamRecoveryTrackerAccess.reset("b")
        assertTrue(tracker("b"))
    }
}
