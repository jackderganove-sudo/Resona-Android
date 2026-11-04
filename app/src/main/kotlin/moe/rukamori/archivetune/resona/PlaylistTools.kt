/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import moe.rukamori.archivetune.resona.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

/**
 * Playlist tools (Resona): remove duplicates, split by main artist, export
 * as CSV. Same rules as Resona for iPad.
 */
object ResonaPlaylistTools {
    data class ToolSong(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSeconds: Int,
        val position: Int,
    )

    fun from(songs: List<PlaylistSong>): List<ToolSong> =
        songs.map {
            ToolSong(
                songId = it.song.song.id,
                title = it.song.song.title,
                artist = it.song.artists.firstOrNull()?.name.orEmpty(),
                album = it.song.album?.title ?: it.song.song.albumName,
                durationSeconds = it.song.song.duration,
                position = it.map.position,
            )
        }

    private fun normalize(text: String) =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == ' ' }.trim()

    /** Positions of every entry after the first with the same song id or the same title + main artist. */
    fun duplicatePositions(songs: List<ToolSong>): List<Int> {
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        val result = ArrayList<Int>()
        for (song in songs.sortedBy { it.position }) {
            val key = normalize(song.title) + "|" + normalize(song.artist)
            val duplicate = !seenIds.add(song.songId) || (song.title.isNotBlank() && !seenKeys.add(key))
            if (duplicate) result += song.position
            seenKeys += key
        }
        return result
    }

    /** Groups by main artist; groups smaller than [minGroup] go together into [otherName]. */
    fun splitByArtist(songs: List<ToolSong>, minGroup: Int = 3, otherName: String = "Other"): List<Pair<String, List<ToolSong>>> {
        val groups = songs.sortedBy { it.position }.groupBy { it.artist.ifBlank { otherName } }
        val big = groups.filter { it.value.size >= minGroup && it.key != otherName }
        val rest = songs.sortedBy { it.position }.filter { song -> big.values.none { group -> song in group } }
        val result = big.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
        return if (rest.isEmpty()) result else result + (otherName to rest)
    }

    fun csv(songs: List<ToolSong>): String {
        fun field(value: String) =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
        fun duration(seconds: Int): String {
            if (seconds <= 0) return ""
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
        val lines = mutableListOf(listOf("Title", "Artists", "Album", "Duration", "Video ID", "URL").joinToString(",") { field(it) })
        songs.sortedBy { it.position }.forEach { song ->
            lines += listOf(
                song.title,
                song.artist,
                song.album.orEmpty(),
                duration(song.durationSeconds),
                song.songId,
                "https://music.youtube.com/watch?v=${song.songId}",
            ).joinToString(",") { field(it) }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    suspend fun removeDuplicates(database: MusicDatabase, playlistId: String, songs: List<PlaylistSong>): Int {
        val positions = duplicatePositions(from(songs)).sortedDescending()
        if (positions.isEmpty()) return 0
        val maps = songs.associateBy { it.map.position }
        withContext(Dispatchers.IO) {
            database.withTransaction {
                for (position in positions) {
                    val map = maps[position]?.map ?: continue
                    move(playlistId, map.position, Int.MAX_VALUE)
                    delete(map.copy(position = Int.MAX_VALUE))
                }
            }
        }
        return positions.size
    }

    suspend fun createSplitPlaylists(database: MusicDatabase, baseName: String, groups: List<Pair<String, List<ToolSong>>>): Int {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                groups.forEach { (name, songs) ->
                    val playlist = PlaylistEntity(name = "$baseName – $name", bookmarkedAt = LocalDateTime.now(), isEditable = true)
                    insert(playlist)
                    songs.forEachIndexed { index, song ->
                        insert(PlaylistSongMap(playlistId = playlist.id, songId = song.songId, position = index))
                    }
                }
            }
        }
        return groups.size
    }

    fun shareCsv(context: Context, title: String, songs: List<PlaylistSong>) {
        val folder = File(context.cacheDir, "csv-export").apply { mkdirs() }
        val safe = title.replace(Regex("[/\\\\:?%*|\"<>]"), "-").trim().ifBlank { "Playlist" }
        val file = File(folder, "$safe.csv")
        file.outputStream().use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(csv(from(songs)).toByteArray(Charsets.UTF_8))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun ResonaPlaylistToolsDialog(
    database: MusicDatabase,
    playlistId: String,
    playlistName: String,
    songs: List<PlaylistSong>,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toolSongs = remember(songs) { ResonaPlaylistTools.from(songs) }
    val duplicates = remember(toolSongs) { ResonaPlaylistTools.duplicatePositions(toolSongs).size }
    val otherName = stringResource(R.string.resona_playlist_split_other)
    val groups = remember(toolSongs, otherName) { ResonaPlaylistTools.splitByArtist(toolSongs, otherName = otherName) }
    val removedText = pluralStringResource(R.plurals.resona_duplicates_removed, duplicates, duplicates)
    val createdText = pluralStringResource(R.plurals.resona_playlists_created, groups.size, groups.size)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resona_playlist_tools)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.resona_remove_duplicates)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.resona_duplicates_found, duplicates, duplicates)) },
                    leadingContent = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = duplicates > 0) {
                        scope.launch {
                            ResonaPlaylistTools.removeDuplicates(database, playlistId, songs)
                            onResult(removedText)
                        }
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.resona_split_by_artist)) },
                    supportingContent = { Text(stringResource(R.string.resona_split_by_artist_desc, groups.size)) },
                    leadingContent = { Icon(painterResource(R.drawable.artist), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = groups.size > 1) {
                        scope.launch {
                            ResonaPlaylistTools.createSplitPlaylists(database, playlistName, groups)
                            onResult(createdText)
                        }
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.resona_export_csv)) },
                    supportingContent = { Text(stringResource(R.string.resona_export_csv_desc)) },
                    leadingContent = { Icon(painterResource(R.drawable.share), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = songs.isNotEmpty()) {
                        ResonaPlaylistTools.shareCsv(context, playlistName, songs)
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
