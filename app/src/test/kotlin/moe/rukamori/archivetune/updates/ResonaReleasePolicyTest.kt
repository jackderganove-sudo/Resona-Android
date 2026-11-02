/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.updates

import moe.rukamori.archivetune.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug: a release list from ArchiveTune (15.1.0) made Resona 1.0.0
 * "two stable releases behind" and locked the app. These rules make sure
 * that can't happen again.
 */
class ResonaReleasePolicyTest {
    private val owner = BuildConfig.RELEASE_GITHUB_OWNER
    private val repo = BuildConfig.RELEASE_GITHUB_REPO

    @Test
    fun buildPointsAtResonasOwnRepository() {
        assertEquals("jackderganove-sudo", BuildConfig.RELEASE_GITHUB_OWNER)
        assertEquals("Resona-Android", BuildConfig.RELEASE_GITHUB_REPO)
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("dev.jackderganove.resona"))
    }

    @Test
    fun neverForcesAnUpdate() {
        assertFalse(ResonaReleasePolicy.FORCE_UPDATES)
        for (newer in 0..20) assertFalse("newer=$newer", ResonaReleasePolicy.isMandatory(newer))
    }

    @Test
    fun archiveTuneReleasesAreIgnored() {
        assertFalse(
            ResonaReleasePolicy.isOwnRelease(
                htmlUrl = "https://github.com/rukamori/ArchiveTune/releases/tag/v15.1.0",
                downloadUrl = "https://github.com/rukamori/ArchiveTune/releases/download/v15.1.0/app-gms-mobile-arm64-release.apk",
                owner = owner, repo = repo,
            ),
        )
        // Own release page, foreign download: still not ours.
        assertFalse(
            ResonaReleasePolicy.isOwnRelease(
                htmlUrl = "https://github.com/$owner/$repo/releases/tag/v1.0.1",
                downloadUrl = "https://example.com/app.apk",
                owner = owner, repo = repo,
            ),
        )
    }

    @Test
    fun ownReleasesCount() {
        assertTrue(
            ResonaReleasePolicy.isOwnRelease(
                htmlUrl = "https://github.com/$owner/$repo/releases/tag/v1.0.1",
                downloadUrl = "https://github.com/$owner/$repo/releases/download/v1.0.1/app-gms-mobile-arm64-release.apk",
                owner = owner, repo = repo,
            ),
        )
        assertTrue(ResonaReleasePolicy.isOwnDownload("https://github.com/$owner/$repo/releases/download/v1.0.1/x.apk", owner, repo))
        assertFalse(ResonaReleasePolicy.isOwnDownload("https://github.com/rukamori/ArchiveTune/releases/download/v15.1.0/x.apk", owner, repo))
    }

    @Test
    fun foreignOrMissingCacheIsNotUsed() {
        assertFalse(ResonaReleasePolicy.isCacheUsable(null, owner, repo))
        assertFalse(ResonaReleasePolicy.isCacheUsable("rukamori/archivetune", owner, repo))
        assertTrue(ResonaReleasePolicy.isCacheUsable(ResonaReleasePolicy.repositoryKey(owner, repo), owner, repo))
    }

    @Test
    fun servicesOfArchiveTuneAreOff() {
        assertFalse(ResonaFlags.SHOW_SUPPORT_PROMPT)
        assertFalse(ResonaFlags.TOGETHER_ONLINE_ENABLED)
        assertFalse(ResonaFlags.NEWS_ENABLED)
    }
}
