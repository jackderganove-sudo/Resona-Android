/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/**
 * Names, manifest and sync plan shared by "downloads as files" (round 12,
 * point 3) and the PS5 stick (point 4): the same rules and the same manifest
 * format in the app's folder and on the stick.
 */
object ResonaFileNames {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)

    /** FAT32/exFAT-safe: forbidden characters → "_", no leading/trailing dots or spaces, length-limited. */
    fun safe(name: String, maxLength: Int = 80): String {
        var out = Normalizer.normalize(name, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")
            .replace(forbidden, "_")
            .trim()
            .trim('.', ' ')
        if (out.length > maxLength) {
            // Never cut a surrogate pair in half.
            var cut = maxLength
            if (Character.isHighSurrogate(out[cut - 1])) cut--
            out = out.substring(0, cut).trimEnd('.', ' ')
        }
        if (out.isEmpty()) out = "_"
        if (reserved.matches(out)) out += "_"
        return out
    }

    /** "03 - Artist - Title.m4a" (playlist) or "Artist - Title.m4a" (no number). */
    fun song(number: Int?, artist: String, title: String, extension: String = "m4a"): String {
        val base = listOfNotNull(
            number?.let { String.format(Locale.ROOT, "%02d", it) },
            artist.takeIf { it.isNotBlank() }?.let { safe(it, 60) },
            safe(title, 90),
        ).joinToString(" - ")
        return safe(base, 160) + "." + extension
    }

    /** [name] or "name (2).ext" … — case-insensitive, as FAT32 compares names. */
    fun unique(name: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (name.lowercase(Locale.ROOT) !in lower) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in lower) return candidate
            n++
        }
    }
}

/** One song file, relative to the root ("Songs/…", "<Playlist>/…"). */
@Serializable
data class ManifestSong(
    val path: String,
    val size: Long = 0,
    val title: String = "",
    val artist: String = "",
    /** File format version ([ResonaFileFormat.CURRENT]); older files are written again. */
    val format: Int = 0,
)

object ResonaFileFormat {
    /**
     * 1 = MediaMuxer .m4a (brand mp42, one 64-bit chunk, Android meta box – the PS5
     * refused it); 2 = [M4aWriter] like the iPad, JPEG cover ≤ 600 px.
     */
    const val CURRENT = 2
}

/** Manifest kept next to the files (".resona-manifest.json"; on the stick Music/.resona-sync.json). */
@Serializable
data class ResonaManifest(
    val version: Int = 1,
    /** Song id → file. */
    val songs: Map<String, ManifestSong> = emptyMap(),
    /** Playlist id → folder name. */
    val folders: Map<String, String> = emptyMap(),
    /** Playlist id → song ids in order (for the playlist folder). */
    val playlists: Map<String, List<String>> = emptyMap(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun decode(text: String?): ResonaManifest =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: ResonaManifest()
    }
}

/** What the files should look like: playlist folders with numbered songs, the rest in "Songs". */
data class WantedPlaylist(val id: String, val name: String, val songIds: List<String>)

data class SongInfo(val id: String, val title: String, val artist: String)

sealed interface FileStep {
    /** New or not yet in that place: write (from an existing copy or after converting). */
    data class Add(val songId: String, val path: String) : FileStep

    /** Same song, other place or name (renumbered, folder renamed): move. */
    data class Move(val songId: String, val from: String, val to: String) : FileStep

    /** No longer wanted there: delete (only files the manifest knows). */
    data class Delete(val songId: String, val path: String) : FileStep

    data class RenameFolder(val playlistId: String, val from: String, val to: String) : FileStep

    /** Playlist no longer selected: its folder (only our files in it) goes. */
    data class DeleteFolder(val playlistId: String, val folder: String) : FileStep
}

object ResonaFilePlan {
    const val SONGS_FOLDER = "Songs"

    /**
     * Steps from [old] to the wanted state. [copiesPerPlaylist]: every
     * playlist folder gets its own copy (the stick); otherwise each song
     * exists once — in the first playlist that has it, else in "Songs".
     */
    fun plan(
        old: ResonaManifest,
        playlists: List<WantedPlaylist>,
        looseSongs: List<String>,
        info: (String) -> SongInfo?,
        copiesPerPlaylist: Boolean,
        flat: Boolean = false,
    ): Pair<List<FileStep>, ResonaManifest> {
        val steps = ArrayList<FileStep>()
        // Folder names: safe and unique, the "Songs" folder kept free.
        val folders = LinkedHashMap<String, String>()
        val takenFolders = HashSet<String>().apply { if (!copiesPerPlaylist) add(SONGS_FOLDER) }
        for (p in playlists) {
            val name = ResonaFileNames.unique(ResonaFileNames.safe(p.name), takenFolders)
            takenFolders += name
            folders[p.id] = name
        }
        // Flat (all files directly in the root, "<Playlist> - NN - Artist - Title"): no folders to rename or drop.
        if (!flat) {
            for ((id, oldName) in old.folders) {
                val newName = folders[id]
                if (newName == null) steps += FileStep.DeleteFolder(id, oldName)
                else if (newName != oldName) steps += FileStep.RenameFolder(id, oldName, newName)
            }
        }

        // Wanted path per (song, place). Key: songId for single copies, "playlist/songId" for copies.
        val wanted = LinkedHashMap<String, String>()
        val takenNames = HashMap<String, MutableSet<String>>()
        fun place(key: String, folder: String, number: Int?, songId: String) {
            val song = info(songId) ?: return
            val bucket = if (flat) "" else folder
            val names = takenNames.getOrPut(bucket) { HashSet() }
            val base = ResonaFileNames.song(number, song.artist, song.title)
            val name = ResonaFileNames.unique(if (flat) ResonaFileNames.safe("$folder - $base", 200) else base, names)
            names += name
            wanted[key] = if (flat) name else "$folder/$name"
        }
        for (p in playlists) {
            val folder = folders.getValue(p.id)
            p.songIds.distinct().forEachIndexed { index, songId ->
                val key = if (copiesPerPlaylist) "${p.id}/$songId" else songId
                if (!copiesPerPlaylist && key in wanted) return@forEachIndexed
                place(key, folder, index + 1, songId)
            }
        }
        if (!copiesPerPlaylist) {
            looseSongs.distinct().filter { it !in wanted }.forEach { place(it, SONGS_FOLDER, null, it) }
        }

        // Old paths as they are after folder renames.
        val renamed = steps.filterIsInstance<FileStep.RenameFolder>().associate { it.from to it.to }
        fun afterRename(path: String): String {
            if ('/' !in path) return path
            val folder = path.substringBefore('/')
            return renamed[folder]?.let { "$it/${path.substringAfter('/')}" } ?: path
        }
        val deletedFolders = steps.filterIsInstance<FileStep.DeleteFolder>().map { it.folder }.toSet()
        val songs = LinkedHashMap<String, ManifestSong>()
        for ((key, oldSong) in old.songs) {
            val path = afterRename(oldSong.path)
            val target = wanted[key]
            when {
                target == null -> if ('/' !in oldSong.path || oldSong.path.substringBefore('/') !in deletedFolders) steps += FileStep.Delete(key.substringAfter('/'), path)
                target != path -> {
                    steps += FileStep.Move(key.substringAfter('/'), path, target)
                    songs[key] = oldSong.copy(path = target)
                }
                else -> songs[key] = oldSong.copy(path = target)
            }
        }
        for ((key, path) in wanted) {
            if (key !in songs) {
                val id = key.substringAfter('/')
                steps += FileStep.Add(id, path)
                val song = info(id)
                songs[key] = ManifestSong(path, 0, song?.title.orEmpty(), song?.artist.orEmpty())
            }
        }
        // Renames first (moves may go into a renamed folder), deletes before moves (a
        // freed name), moves out of a dropped folder before that folder goes, adds last.
        val ordered = steps.filterIsInstance<FileStep.RenameFolder>() + steps.filterIsInstance<FileStep.Delete>() +
            steps.filterIsInstance<FileStep.Move>() + steps.filterIsInstance<FileStep.DeleteFolder>() + steps.filterIsInstance<FileStep.Add>()
        val manifest = ResonaManifest(
            songs = songs,
            folders = folders,
            playlists = playlists.associate { it.id to it.songIds.distinct() },
        )
        return ordered to manifest
    }
}
