/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 12, found in the Pixel's error log: a client refused with HTTP 403 was
 * tried again four times, because refreshing the session cleared the list of
 * failed clients and changed the auth fingerprint in its key.
 */
class FailedStreamClientTest {
    private val video = "testVideo01"

    @Test
    fun refusedClientStaysBlockedAfterSessionRefresh() {
        YTPlayerUtils.clearFailedStreamClients(video)
        YTPlayerUtils.markStreamClientFailed(video, "WEB_CREATOR", 403, authFingerprint = "before")
        YTPlayerUtils.clearPlaybackAuthCaches()
        assertTrue(YTPlayerUtils.isStreamClientTemporarilyBlocked(video, "WEB_CREATOR", authFingerprint = "after"))
        assertFalse(YTPlayerUtils.isStreamClientTemporarilyBlocked(video, "WEB_REMIX", authFingerprint = "after"))
    }

    @Test
    fun retryForgetsTheSongsFailedClients() {
        YTPlayerUtils.markStreamClientFailed(video, "WEB_CREATOR", 403, authFingerprint = "x")
        YTPlayerUtils.clearFailedStreamClients(video)
        assertFalse(YTPlayerUtils.isStreamClientTemporarilyBlocked(video, "WEB_CREATOR", authFingerprint = "x"))
    }

    @Test
    fun otherErrorsDoNotBlock() {
        YTPlayerUtils.clearFailedStreamClients(video)
        YTPlayerUtils.markStreamClientFailed(video, "WEB", 500, authFingerprint = "x")
        assertFalse(YTPlayerUtils.isStreamClientTemporarilyBlocked(video, "WEB", authFingerprint = "x"))
    }
}
