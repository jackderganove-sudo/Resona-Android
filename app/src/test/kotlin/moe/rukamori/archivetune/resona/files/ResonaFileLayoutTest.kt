/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented titles and artists only. */
class ResonaFileLayoutTest {
    private val songs =
        mapOf(
            "a" to SongInfo("a", "Erfundener Titel", "Platzhalter Band"),
            "b" to SongInfo("b", "Was? Wie: Wo*", "AC/DC-Ersatz"),
            "c" to SongInfo("c", "Dritter Song", "Niemand"),
            "d" to SongInfo("d", "Erfundener Titel", "Platzhalter Band"),
        )
    private val info: (String) -> SongInfo? = { songs[it] }

    @Test
    fun namesAreFat32Safe() {
        assertEquals("Was_ Wie_ Wo_", ResonaFileNames.safe("Was? Wie: Wo*"))
        assertEquals("AC_DC", ResonaFileNames.safe("AC/DC"))
        assertEquals("_", ResonaFileNames.safe("..."))
        assertEquals("CON_", ResonaFileNames.safe("con".uppercase()))
        assertEquals("a b", ResonaFileNames.safe("  a\t\nb . "))
        assertTrue(ResonaFileNames.safe("x".repeat(500)).length <= 80)
        val emoji = ResonaFileNames.safe("a".repeat(79) + "🎵")
        assertFalse(Character.isHighSurrogate(emoji.last()))
        assertEquals("03 - Platzhalter Band - Erfundener Titel.m4a", ResonaFileNames.song(3, "Platzhalter Band", "Erfundener Titel"))
        assertEquals("Niemand - Dritter Song.m4a", ResonaFileNames.song(null, "Niemand", "Dritter Song"))
    }

    @Test
    fun collisionsGetANumberCaseInsensitive() {
        assertEquals("x (2).m4a", ResonaFileNames.unique("x.m4a", setOf("X.M4A")))
        assertEquals("x (3).m4a", ResonaFileNames.unique("x.m4a", setOf("x.m4a", "x (2).m4a")))
        assertEquals("y.m4a", ResonaFileNames.unique("y.m4a", setOf("x.m4a")))
    }

    @Test
    fun manifestRoundTripAndBrokenFile() {
        val m = ResonaManifest(songs = mapOf("a" to ManifestSong("Songs/a.m4a", 12, "t", "k")), folders = mapOf("p" to "Mix"))
        assertEquals(m, ResonaManifest.decode(m.encode()))
        assertEquals(ResonaManifest(), ResonaManifest.decode("{kaputt"))
        assertEquals(ResonaManifest(), ResonaManifest.decode(null))
    }

    @Test
    fun firstSyncAddsEverythingOnce() {
        val (steps, manifest) =
            ResonaFilePlan.plan(ResonaManifest(), listOf(WantedPlaylist("p1", "Mein Mix", listOf("a", "b"))), listOf("a", "c"), info, copiesPerPlaylist = false)
        assertEquals(3, steps.size)
        assertTrue(steps.all { it is FileStep.Add })
        assertEquals("Mein Mix/01 - Platzhalter Band - Erfundener Titel.m4a", manifest.songs.getValue("a").path)
        assertEquals("Mein Mix/02 - AC_DC-Ersatz - Was_ Wie_ Wo_.m4a", manifest.songs.getValue("b").path)
        assertEquals("Songs/Niemand - Dritter Song.m4a", manifest.songs.getValue("c").path)
    }

    @Test
    fun unchangedSyncDoesNothing() {
        val playlists = listOf(WantedPlaylist("p1", "Mein Mix", listOf("a", "b")))
        val (_, first) = ResonaFilePlan.plan(ResonaManifest(), playlists, emptyList(), info, false)
        val (steps, second) = ResonaFilePlan.plan(first, playlists, emptyList(), info, false)
        assertTrue(steps.isEmpty())
        assertEquals(first, second)
    }

    @Test
    fun removedSongRenamedPlaylistAndRenumbering() {
        val (_, first) = ResonaFilePlan.plan(ResonaManifest(), listOf(WantedPlaylist("p1", "Mein Mix", listOf("a", "b", "c"))), emptyList(), info, false)
        val (steps, second) = ResonaFilePlan.plan(first, listOf(WantedPlaylist("p1", "Neuer Name", listOf("b", "c"))), emptyList(), info, false)
        assertEquals(FileStep.RenameFolder("p1", "Mein Mix", "Neuer Name"), steps[0])
        assertTrue(FileStep.Delete("a", "Neuer Name/01 - Platzhalter Band - Erfundener Titel.m4a") in steps)
        assertTrue(FileStep.Move("b", "Neuer Name/02 - AC_DC-Ersatz - Was_ Wie_ Wo_.m4a", "Neuer Name/01 - AC_DC-Ersatz - Was_ Wie_ Wo_.m4a") in steps)
        assertFalse(steps.any { it is FileStep.Add })
        assertFalse("a" in second.songs)
    }

    @Test
    fun songLeavingADroppedPlaylistMovesToSongsBeforeTheFolderGoes() {
        val (_, first) = ResonaFilePlan.plan(ResonaManifest(), listOf(WantedPlaylist("p1", "Mix", listOf("a"))), listOf("a"), info, false)
        val (steps, second) = ResonaFilePlan.plan(first, emptyList(), listOf("a"), info, false)
        val move = steps.indexOfFirst { it is FileStep.Move }
        val dropFolder = steps.indexOfFirst { it is FileStep.DeleteFolder }
        assertTrue(move in 0 until dropFolder)
        assertEquals("Songs/Platzhalter Band - Erfundener Titel.m4a", second.songs.getValue("a").path)
    }

    @Test
    fun stickGetsACopyPerPlaylistAndSameNamesGetANumber() {
        val playlists = listOf(WantedPlaylist("p1", "Eins", listOf("a", "d")), WantedPlaylist("p2", "Zwei", listOf("a")))
        val (steps, manifest) = ResonaFilePlan.plan(ResonaManifest(), playlists, emptyList(), info, copiesPerPlaylist = true)
        assertEquals(3, steps.count { it is FileStep.Add })
        assertEquals("Eins/01 - Platzhalter Band - Erfundener Titel.m4a", manifest.songs.getValue("p1/a").path)
        assertEquals("Eins/02 - Platzhalter Band - Erfundener Titel.m4a", manifest.songs.getValue("p1/d").path)
        assertEquals("Zwei/01 - Platzhalter Band - Erfundener Titel.m4a", manifest.songs.getValue("p2/a").path)
        // Deselected playlist: its folder goes, nothing else.
        val (again, _) = ResonaFilePlan.plan(manifest, playlists.take(1), emptyList(), info, true)
        assertEquals(listOf(FileStep.DeleteFolder("p2", "Zwei")), again)
    }

    @Test
    fun samePlaylistNamesGetUniqueFolders() {
        val (_, manifest) =
            ResonaFilePlan.plan(
                ResonaManifest(),
                listOf(WantedPlaylist("p1", "Mix", listOf("a")), WantedPlaylist("p2", "mix", listOf("c")), WantedPlaylist("p3", "Songs", listOf("b"))),
                emptyList(), info, false,
            )
        assertEquals(mapOf("p1" to "Mix", "p2" to "mix (2)", "p3" to "Songs (2)"), manifest.folders)
    }
}
