/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import moe.rukamori.archivetune.resona.ResonaPlaylistTools.ToolSong
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistToolsTest {
    private fun song(id: String, title: String, artist: String, position: Int, album: String? = null, duration: Int = 200) =
        ToolSong(id, title, artist, album, duration, position)

    @Test
    fun duplicatesBySongIdOrTitleAndArtist() {
        val songs = listOf(
            song("a", "Erfundenes Lied", "Band Eins", 0),
            song("b", "Anderes Lied", "Band Zwei", 1),
            song("a", "Erfundenes Lied", "Band Eins", 2),
            song("c", "erfundenes lied!", "band eins", 3),
            song("d", "Anderes Lied", "Band Drei", 4),
        )
        assertEquals(listOf(2, 3), ResonaPlaylistTools.duplicatePositions(songs))
    }

    @Test
    fun smallArtistGroupsGoToOther() {
        val songs = (0 until 4).map { song("a$it", "A$it", "Band A", it) } +
            (0 until 3).map { song("b$it", "B$it", "Band B", 10 + it) } +
            listOf(song("c", "C", "Solo", 20), song("d", "D", "", 21))
        val groups = ResonaPlaylistTools.splitByArtist(songs, minGroup = 3, otherName = "Sonstige")
        assertEquals(listOf("Band A", "Band B", "Sonstige"), groups.map { it.first })
        assertEquals(listOf("c", "d"), groups.last().second.map { it.songId })
    }

    @Test
    fun csvQuotesFields() {
        val csv = ResonaPlaylistTools.csv(listOf(song("x1", "Teil \"2\", Mix", "Band", 0, album = "Album", duration = 3725)))
        val lines = csv.split("\r\n")
        assertEquals("Title,Artists,Album,Duration,Video ID,URL", lines[0])
        assertEquals("\"Teil \"\"2\"\", Mix\",Band,Album,1:02:05,x1,https://music.youtube.com/watch?v=x1", lines[1])
    }
}
