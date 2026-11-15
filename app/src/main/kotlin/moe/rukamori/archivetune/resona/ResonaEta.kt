/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import moe.rukamori.archivetune.R

/** Remaining time for long jobs (downloads as files, PS5 stick) – round 12. */
object ResonaEta {
    /** Seconds left from the rate so far; null until 2 s and some progress. */
    fun remaining(startedAtMs: Long, done: Long, total: Long, nowMs: Long = System.currentTimeMillis()): Long? {
        val seconds = (nowMs - startedAtMs) / 1000.0
        if (startedAtMs <= 0 || done <= 0 || seconds < 2 || total <= done) return null
        return ((total - done) * seconds / done).toLong()
    }

    fun text(context: Context, seconds: Long): String =
        if (seconds >= 90) context.getString(R.string.resona_eta_minutes, ((seconds + 30) / 60).toInt())
        else context.getString(R.string.resona_eta_seconds, maxOf(seconds, 1L).toInt())
}
