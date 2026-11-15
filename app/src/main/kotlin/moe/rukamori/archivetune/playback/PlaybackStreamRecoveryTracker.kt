/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

/**
 * Resona: a song gets several automatic retries (one per YouTube client that
 * refuses it) until it plays again – with a single retry, a second refused
 * client after a long pause ended in "No stream available" (HTTP 403), and
 * "Retry" in the error box changed nothing. [reset] = the user's "Retry".
 */
internal class PlaybackStreamRecoveryTracker(private val maxAttempts: Int = MAX_ATTEMPTS) {
    private var attemptedMediaId: String? = null
    private var attempts = 0

    fun registerRetryAttempt(mediaId: String): Boolean {
        if (attemptedMediaId != mediaId) {
            attemptedMediaId = mediaId
            attempts = 0
        }
        if (attempts >= maxAttempts) return false
        attempts++
        return true
    }

    fun onPlaybackRecovered(mediaId: String?) {
        if (mediaId != null && attemptedMediaId == mediaId) {
            attemptedMediaId = null
            attempts = 0
        }
    }

    fun onMediaItemChanged(currentMediaId: String?) {
        if (attemptedMediaId != currentMediaId) {
            attemptedMediaId = null
            attempts = 0
        }
    }

    fun reset(mediaId: String?) {
        if (mediaId == null || attemptedMediaId == mediaId) {
            attemptedMediaId = null
            attempts = 0
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 4
    }
}
