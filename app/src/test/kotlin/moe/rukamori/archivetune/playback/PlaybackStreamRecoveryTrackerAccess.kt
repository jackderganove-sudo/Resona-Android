/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.playback

/** The tracker is internal to the playback package: a small door for the tests. */
object PlaybackStreamRecoveryTrackerAccess {
    private var tracker = PlaybackStreamRecoveryTracker()

    fun create(maxAttempts: Int): (String) -> Boolean {
        tracker = PlaybackStreamRecoveryTracker(maxAttempts)
        return { id -> tracker.registerRetryAttempt(id) }
    }

    fun reset(id: String) = tracker.reset(id)
}
