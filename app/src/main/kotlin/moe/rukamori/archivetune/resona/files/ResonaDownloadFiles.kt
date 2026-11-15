/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.downloads.DownloadedArtworkRepository
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads as real files (round 12, point 3): every finished download is
 * written as .m4a (AAC, tags, cover) into the Resona folder — playlists as
 * "<Playlist>/NN - Artist - Title.m4a", other songs in "Songs/". A
 * ".nomedia" keeps them out of the media library and other music apps. Once
 * the file exists, the download cache copy is dropped and playback reads the
 * file (no space used twice). Only files the manifest knows are ever moved
 * or deleted.
 */
@Singleton
class ResonaDownloadFiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @DownloadCache private val downloadCache: Cache,
        private val artwork: DownloadedArtworkRepository,
    ) {
        data class Progress(val done: Int, val total: Int, val bytes: Long)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val uris = ConcurrentHashMap<String, Uri>()
        private var manifest = ResonaManifest()
        private var started: Job? = null

        private val _progress = MutableStateFlow<Progress?>(null)
        /** While songs are converted (first take-over after the update too); null when idle. */
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        var enabled: Boolean
            get() = prefs.getBoolean(KEY_ENABLED, true)
            set(value) {
                prefs.edit().putBoolean(KEY_ENABLED, value).apply()
                if (value) requestSync()
            }

        /** A folder the user picked (SAF); null = the default folder. */
        var chosenFolder: Uri?
            get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
            set(value) {
                prefs.edit().putString(KEY_TREE, value?.toString()).apply()
                uris.clear()
                requestSync()
            }

        /** Readable place for settings ("Music/Resona" …). */
        fun folderLabel(): String =
            chosenFolder?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: defaultDir().let { dir ->
                dir.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath).trimStart('/')
            }

        @Volatile private var completedIds: Set<String> = emptySet()

        /** Called once from the app: follows downloads and playlists. */
        @OptIn(FlowPreview::class)
        fun start(downloads: StateFlow<Map<String, Download>>) {
            if (started != null) return
            loadManifest()
            started =
                scope.launch {
                    combine(
                        downloads.map { map -> map.filterValues { it.state == Download.STATE_COMPLETED }.keys }.distinctUntilChanged(),
                        database.allPlaylistsForDownloads(),
                        database.allPlaylistSongMapsForDownloads(),
                    ) { ids, playlists, maps -> Triple(ids, playlists, maps) }
                        .debounce(3_000)
                        .collect { (ids, playlists, maps) ->
                            completedIds = ids
                            if (!enabled) return@collect
                            val byPlaylist = maps.groupBy { it.playlistId }
                            val wanted =
                                playlists.mapNotNull { p ->
                                    val songs = byPlaylist[p.playlist.id].orEmpty().sortedBy { it.position }.map { it.songId }.filter { it in ids }
                                    songs.takeIf { it.isNotEmpty() }?.let { WantedPlaylist(p.playlist.id, p.playlist.name, it) }
                                }
                            last = wanted to ids.toList()
                            sync(wanted, ids.toList())
                        }
                }
        }

        @Volatile private var last: Pair<List<WantedPlaylist>, List<String>>? = null

        /** Again with the last known downloads and playlists (setting or folder changed). */
        fun requestSync() {
            val (playlists, ids) = last ?: return
            if (!enabled) return
            scope.launch {
                loadManifest()
                sync(playlists, ids)
            }
        }

        /** The song's file, when it has one (playback, AI sync). */
        fun uriFor(songId: String): Uri? {
            if (!enabled && manifest.songs.isEmpty()) return null
            uris[songId]?.let { return it }
            val song = manifest.songs[songId] ?: return null
            val uri = resolve(song.path)?.takeIf { it.exists() && it.length() > 0 }?.uri ?: return null
            uris[songId] = uri
            return uri
        }

        private fun defaultDir(): File {
            // Music/Resona when the app may write there, else the app's own media folder.
            @Suppress("DEPRECATION")
            val music = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Resona")
            if (prefs.getString(KEY_DEFAULT, null) == "music" || (prefs.getString(KEY_DEFAULT, null) == null && tryPrepare(music))) {
                prefs.edit().putString(KEY_DEFAULT, "music").apply()
                return music
            }
            val media = File(context.externalMediaDirs.firstOrNull() ?: context.getExternalFilesDir(null)!!, "Resona")
            prefs.edit().putString(KEY_DEFAULT, "media").apply()
            media.mkdirs()
            return media
        }

        private fun tryPrepare(dir: File): Boolean {
            val existed = dir.exists()
            val ok = runCatching {
                dir.mkdirs()
                val nomedia = File(dir, ".nomedia")
                if (!nomedia.exists()) nomedia.createNewFile()
                nomedia.exists()
            }.getOrDefault(false)
            // Music/ only takes audio files from apps (no .nomedia): don't leave an empty folder behind.
            if (!ok && !existed) runCatching { if (dir.listFiles().isNullOrEmpty()) dir.delete() }
            return ok
        }

        /**
         * A new file named exactly [name]. A plain folder's DocumentFile appends the
         * MIME type's extension (".nomedia.bin", "x.m4a.m4a"), so plain folders use
         * java.io; a picked folder (SAF) gets octet-stream for non-audio names.
         */
        private fun createChild(dir: DocumentFile, mime: String, name: String): DocumentFile? {
            if (dir.uri.scheme == "file") {
                val file = File(dir.uri.path!!, name)
                if (!file.exists() && !runCatching { file.createNewFile() }.getOrDefault(false)) return null
                return DocumentFile.fromFile(file)
            }
            val created = dir.createFile(if (name.endsWith(".m4a")) mime else "application/octet-stream", name) ?: return null
            if (created.name != name) created.renameTo(name)
            return created
        }

        private fun createFolder(parent: DocumentFile, name: String): DocumentFile? {
            if (parent.uri.scheme == "file") {
                val dir = File(parent.uri.path!!, name)
                dir.mkdirs()
                return if (dir.isDirectory) DocumentFile.fromFile(dir) else null
            }
            return parent.createDirectory(name)
        }

        private fun root(): DocumentFile? {
            chosenFolder?.let { tree -> return DocumentFile.fromTreeUri(context, tree) }
            val dir = defaultDir()
            dir.mkdirs()
            return DocumentFile.fromFile(dir)
        }

        private fun resolve(path: String): DocumentFile? {
            var at = root() ?: return null
            for (part in path.split('/')) at = at.findFile(part) ?: return null
            return at
        }

        private fun folder(name: String): DocumentFile? {
            val root = root() ?: return null
            return root.findFile(name)?.takeIf { it.isDirectory } ?: createFolder(root, name)
        }

        private fun loadManifest() {
            manifest = runCatching {
                val file = root()?.findFile(MANIFEST) ?: return@runCatching ResonaManifest()
                context.contentResolver.openInputStream(file.uri)?.use { ResonaManifest.decode(it.readBytes().decodeToString()) }
            }.getOrNull() ?: ResonaManifest()
        }

        private fun saveManifest() {
            runCatching {
                val root = root() ?: return
                val file = root.findFile(MANIFEST) ?: createChild(root, "application/json", MANIFEST) ?: return
                context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(manifest.encode().toByteArray()) }
            }
        }

        private fun ensureNomedia() {
            val root = root() ?: return
            // Leftovers of the first test build (extension added twice).
            root.findFile(".nomedia.bin")?.delete()
            root.findFile("$MANIFEST.json")?.delete()
            if (root.findFile(".nomedia") == null) {
                // Before any audio file, so the media scanner never lists one.
                createChild(root, "application/octet-stream", ".nomedia")
            }
        }

        private suspend fun sync(playlists: List<WantedPlaylist>, downloaded: List<String>) =
            mutex.withLock {
                ensureNomedia()
                // A file deleted outside the app is written again (when its cache copy still exists).
                manifest = manifest.copy(songs = manifest.songs.filterValues { resolve(it.path)?.exists() == true })
                val infos = HashMap<String, SongInfo>()
                for (id in downloaded) {
                    database.getSongById(id)?.let { s ->
                        infos[id] = SongInfo(id, s.song.title, s.artists.joinToString(", ") { it.name })
                    }
                }
                val (steps, next) = ResonaFilePlan.plan(manifest, playlists, downloaded, { infos[it] }, copiesPerPlaylist = false)
                if (steps.isEmpty()) return@withLock
                val adds = steps.count { it is FileStep.Add }
                var done = 0
                var bytes = 0L
                if (adds > 0) {
                    _progress.value = Progress(0, adds, 0)
                    notify(0, adds)
                }
                val sizes = HashMap<String, Long>()
                val exported = HashMap<String, String>()
                val lost = ArrayList<String>()
                for (step in steps) {
                    runCatching {
                        when (step) {
                            is FileStep.RenameFolder -> resolve(step.from)?.renameTo(step.to)
                            is FileStep.DeleteFolder -> deleteOurFiles(step.folder)
                            is FileStep.Delete -> resolve(step.path)?.delete()
                            is FileStep.Move -> move(step.from, step.to)
                            is FileStep.Add -> {
                                val size = repair(step.path) ?: add(step.songId, step.path)
                                if (size > 0) {
                                    sizes[step.songId] = size
                                    exported[step.songId] = step.path
                                } else if (!cacheHas(step.songId)) {
                                    lost += step.songId
                                }
                                done++
                                bytes += size
                                _progress.value = Progress(done, adds, bytes)
                                notify(done, adds)
                            }
                        }
                    }.onFailure { Log.w(TAG, "step ${step.javaClass.simpleName} failed: ${it.javaClass.simpleName}") }
                }
                // Songs whose file could not be written stay out of the manifest (next sync tries again).
                val written = next.songs.filter { (id, song) -> id in sizes || id in manifest.songs || resolve(song.path)?.exists() == true }
                    .mapValues { (id, song) -> sizes[id]?.let { song.copy(size = it) } ?: song }
                manifest = next.copy(songs = written)
                uris.clear()
                saveManifest()
                writePlaylistFiles(next)
                // Only now, with the file in place and in the manifest: free the cache copy.
                for ((id, path) in exported) {
                    if (manifest.songs[id]?.path == path && resolve(path)?.let { it.exists() && it.length() > 0 } == true) {
                        runCatching { downloadCache.removeResource(id) }
                    }
                }
                // Neither file nor cache copy: download again instead of losing the song.
                if (lost.isNotEmpty()) {
                    Log.w(TAG, "${lost.size} downloads had neither file nor cache copy, downloading again")
                    val items = lost.map { id -> HeaderDownloadItem(id = id, title = infos[id]?.title.orEmpty()) }
                    sendRemoveDownloads(context, lost)
                    sendAddMissingDownloads(context, items, emptyMap())
                }
                _progress.value = null
                if (adds > 0) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                Log.i(TAG, "sync: ${steps.size} steps, ${exported.size} of $adds songs written, ${bytes / 1024} KiB, ${lost.size} downloaded again")
            }

        /** "Saving downloads as files – 3 of 120" while songs are converted. */
        @android.annotation.SuppressLint("MissingPermission")
        private fun notify(done: Int, total: Int) {
            if (total < 2) return
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val notification =
                NotificationCompat.Builder(context, moe.rukamori.archivetune.playback.ExoDownloadService.CHANNEL_ID)
                    .setSmallIcon(moe.rukamori.archivetune.R.drawable.ic_download)
                    .setContentTitle(context.getString(moe.rukamori.archivetune.R.string.resona_files_notification))
                    .setContentText(context.getString(moe.rukamori.archivetune.R.string.resona_files_count, done, total))
                    .setProgress(total, done, false)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }

        /** A file the first test build named "x.m4a.m4a": give it its name instead of converting again. */
        private fun repair(path: String): Long? {
            if (resolve(path) != null) return null
            val stray = resolve("$path.m4a") ?: return null
            if (!stray.renameTo(path.substringAfterLast('/'))) return null
            return resolve(path)?.length()?.takeIf { it > 0 }
        }

        private fun cacheHas(key: String): Boolean {
            val length = ContentMetadata.getContentLength(downloadCache.getContentMetadata(key))
            return length > 0 && downloadCache.isCached(key, 0L, length)
        }

        /** Only files this app wrote (manifest), then the folder if it is empty. */
        private fun deleteOurFiles(folderName: String) {
            val dir = resolve(folderName) ?: return
            manifest.songs.values.filter { it.path.substringBefore('/') == folderName }.forEach { resolve(it.path)?.delete() }
            dir.findFile(PLAYLIST_FILE)?.delete()
            if (dir.listFiles().isEmpty()) dir.delete()
        }

        private fun move(from: String, to: String) {
            val source = resolve(from) ?: return
            val targetFolder = folder(to.substringBefore('/')) ?: return
            val name = to.substringAfter('/')
            if (from.substringBefore('/') == to.substringBefore('/')) {
                source.renameTo(name)
                return
            }
            val target = createChild(targetFolder, "audio/mp4", name) ?: return
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { input.copyTo(it) }
            }
            source.delete()
        }

        /** Download cache → temp → .m4a with tags → its place; then the cache copy goes. */
        private suspend fun add(songId: String, path: String): Long {
            val work = File(context.cacheDir, "resona-files").apply { mkdirs() }
            val raw = File(work, "$songId.src")
            val m4a = File(work, "$songId.m4a")
            try {
                if (!copyFromCache(songId, raw)) return 0
                ResonaAudioConverter.toM4a(raw, m4a)
                val song = database.getSongById(songId)
                val cover = song?.song?.thumbnailUrl?.let { artwork.localArtwork(it) }?.takeIf { it.length() in 1..2_000_000 }?.readBytes()
                Mp4Tags.write(
                    m4a,
                    Mp4Tags.Tags(
                        title = song?.song?.title ?: songId,
                        artist = song?.artists?.joinToString(", ") { it.name }.orEmpty(),
                        album = song?.song?.albumName,
                        track = song?.song?.trackNumber,
                        cover = cover,
                    ),
                )
                val folder = folder(path.substringBefore('/')) ?: return 0
                val name = path.substringAfter('/')
                folder.findFile(name)?.delete()
                val target = createChild(folder, "audio/mp4", name) ?: return 0
                context.contentResolver.openOutputStream(target.uri)?.use { out -> m4a.inputStream().use { it.copyTo(out) } }
                return m4a.length()
            } finally {
                raw.delete()
                m4a.delete()
            }
        }

        private fun copyFromCache(key: String, target: File): Boolean {
            val length = ContentMetadata.getContentLength(downloadCache.getContentMetadata(key))
            if (length <= 0 || !downloadCache.isCached(key, 0L, length)) return false
            val source = CacheDataSource(downloadCache, null)
            return try {
                source.open(DataSpec.Builder().setUri(Uri.parse("resona-cache://$key")).setKey(key).setLength(C.LENGTH_UNSET.toLong()).build())
                target.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                    }
                }
                true
            } catch (e: Exception) {
                false
            } finally {
                runCatching { source.close() }
            }
        }

        /** "<Playlist>/playlist.m3u8" so file players keep the order. */
        private fun writePlaylistFiles(m: ResonaManifest) {
            for ((id, folderName) in m.folders) {
                val dir = resolve(folderName) ?: continue
                dir.findFile("$PLAYLIST_FILE.m3u")?.delete()
                // A song lives once: in this folder or, when another playlist has it, there.
                val lines = m.playlists[id].orEmpty().mapNotNull { m.songs[it]?.path }
                    .map { if (it.substringBefore('/') == folderName) it.substringAfter('/') else "../$it" }
                val file = dir.findFile(PLAYLIST_FILE) ?: createChild(dir, "audio/x-mpegurl", PLAYLIST_FILE) ?: continue
                runCatching {
                    context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(("#EXTM3U\n" + lines.joinToString("\n") + "\n").toByteArray()) }
                }
            }
        }

        companion object {
            private const val TAG = "ResonaFiles"
            private const val PREFS = "resona_files"
            private const val KEY_ENABLED = "enabled"
            private const val KEY_TREE = "tree"
            private const val KEY_DEFAULT = "default_root"
            const val MANIFEST = ".resona-manifest.json"
            const val PLAYLIST_FILE = "playlist.m3u8"
            private const val NOTIFICATION_ID = 0x5E50
        }
    }
