/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.updates

/**
 * Resona's update rules:
 * - only releases of Resona's own repository count, and only with an APK
 *   downloaded from that repository (never ArchiveTune's releases, even
 *   from an old cache or a restored ArchiveTune backup);
 * - no forced updates: an update is always a dismissible hint.
 */
object ResonaReleasePolicy {
    /** ArchiveTune locked the app two stable releases behind; Resona never does. */
    const val FORCE_UPDATES = false

    fun repositoryKey(owner: String, repo: String): String = "${owner.lowercase()}/${repo.lowercase()}"

    /** Never blocks the app, however many releases are newer. */
    fun isMandatory(newerStableReleases: Int): Boolean = FORCE_UPDATES && newerStableReleases >= 2

    /** A cached release list only counts when it was fetched from this repository. */
    fun isCacheUsable(storedSource: String?, owner: String, repo: String): Boolean =
        storedSource == repositoryKey(owner, repo)

    fun isOwnRelease(htmlUrl: String, downloadUrl: String?, owner: String, repo: String): Boolean {
        val prefix = "https://github.com/$owner/$repo/"
        if (!htmlUrl.startsWith(prefix, ignoreCase = true)) return false
        return downloadUrl == null || isOwnDownload(downloadUrl, owner, repo)
    }

    fun isOwnDownload(url: String, owner: String, repo: String): Boolean =
        url.startsWith("https://github.com/$owner/$repo/releases/download/", ignoreCase = true)
}

/** Resona: ArchiveTune services that are switched off in this fork. */
object ResonaFlags {
    /** "Star / support rukamori" prompt after a few launches (ArchiveTune's own appeal). */
    const val SHOW_SUPPORT_PROMPT = false

    /** Listen Together online: the server address comes from ArchiveTune's repository. */
    const val TOGETHER_ONLINE_ENABLED = false

    /** News feed from koiverse/ArchiveTuneNewsRepository. */
    const val NEWS_ENABLED = false
}
