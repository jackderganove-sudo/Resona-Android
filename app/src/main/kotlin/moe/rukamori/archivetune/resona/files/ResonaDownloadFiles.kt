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
                            // A folder only for playlists downloaded completely; a song that is
                            // also in other, partly downloaded playlists goes to "Songs/".
                            val wanted =
                                playlists.mapNotNull { p ->
                                    val all = byPlaylist[p.playlist.id].orEmpty().sortedBy { it.position }.map { it.songId }
                                    all.takeIf { it.isNotEmpty() && it.all { id -> id in ids } }?.let { WantedPlaylist(p.playlist.id, p.playlist.name, it) }
                                }
                            last = wanted to ids.toList()
                            generation++
                            // Round 12: written in a foreground worker – as a plain background job
                            // Android moved it to the slow cores once the screen went off
                            // (measured on the Pixel: 22 s → 50 s per song).
                            FilesExportWorker.enqueue(context)
                        }
                }
        }

        @Volatile private var generation = 0

        /** The worker: sync with the newest downloads/playlists until nothing new came in meanwhile. */
        suspend fun runQueued() {
            while (true) {
                val seen = generation
                val (playlists, ids) = last ?: return
                if (!enabled) return
                sync(playlists, ids)
                if (generation == seen) return
            }
        }

        @Volatile private var last: Pair<List<WantedPlaylist>, List<String>>? = null

        /** Again with the last known downloads and playlists (setting or folder changed). */
        fun requestSync() {
            val (playlists, ids) = last ?: return
            if (!enabled) return
            scope.launch {
                loadManifest()
                generation++
                FilesExportWorker.enqueue(context)
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
                // Round 12: keep the CPU awake only while files are written (screen may be off).
                val wake = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Resona:files")
                    .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
                try { syncLocked(playlists, downloaded) } finally { runCatching { wake.release() } }
            }

        private suspend fun syncLocked(playlists: List<WantedPlaylist>, downloaded: List<String>) =
            run {
                ensureNomedia()
                // A file deleted outside the app is written again (when its cache copy still exists).
                manifest = manifest.copy(songs = manifest.songs.filterValues { resolve(it.path)?.exists() == true })
                upgradeFormats()
                val infos = HashMap<String, SongInfo>()
                for (id in downloaded) {
                    database.getSongById(id)?.let { s ->
                        infos[id] = SongInfo(id, s.song.title, s.artists.joinToString(", ") { it.name })
                    }
                }
                val (steps, next) = ResonaFilePlan.plan(manifest, playlists, downloaded, { infos[it] }, copiesPerPlaylist = false)
                if (steps.isEmpty()) return@run
                val adds = steps.count { it is FileStep.Add }
                var done = 0
                var bytes = 0L
                if (adds > 0) {
                    _progress.value = Progress(0, adds, 0)
                    notify(0, adds)
                }
                val sizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
                val exported = java.util.concurrent.ConcurrentHashMap<String, String>()
                val lost = java.util.Collections.synchronizedList(ArrayList<String>())
                // Folders, moves and deletions first (in order), then the new songs.
                for (step in steps.filterNot { it is FileStep.Add }) {
                    runCatching {
                        when (step) {
                            is FileStep.RenameFolder -> resolve(step.from)?.renameTo(step.to)
                            is FileStep.DeleteFolder -> deleteOurFiles(step.folder)
                            is FileStep.Delete -> resolve(step.path)?.delete()
                            is FileStep.Move -> move(step.from, step.to)
                            is FileStep.Add -> Unit
                        }
                    }.onFailure { Log.w(TAG, "step ${step.javaClass.simpleName} failed: ${it.javaClass.simpleName}") }
                }
                // Round 12: songs are written EXPORT_PARALLEL at a time (Opus songs still need
                // an encode; each encoder is single-threaded), and the manifest is saved every
                // CHECKPOINT_SONGS songs – before, it was saved only at the very end, so an
                // interrupted run (app closed, process killed) started all 200 songs again.
                val progressLock = kotlinx.coroutines.sync.Mutex()
                var sinceCheckpoint = 0
                var checkpointAt = System.currentTimeMillis()
                fun manifestSoFar(): ResonaManifest =
                    next.copy(
                        songs = next.songs.filter { (id, song) -> sizes.containsKey(id) || id in manifest.songs || resolve(song.path)?.exists() == true }
                            .mapValues { (id, song) ->
                                when {
                                    sizes.containsKey(id) -> song.copy(size = sizes.getValue(id), format = ResonaFileFormat.CURRENT)
                                    else -> song.copy(format = manifest.songs[id]?.format ?: song.format)
                                }
                            },
                    )
                val semaphore = kotlinx.coroutines.sync.Semaphore(EXPORT_PARALLEL)
                kotlinx.coroutines.coroutineScope {
                    for (step in steps.filterIsInstance<FileStep.Add>()) {
                        launch(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                val size = runCatching { repair(step.path) ?: add(step.songId, step.path) }
                                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "add failed: ${it.javaClass.simpleName}") }
                                    .getOrDefault(0L)
                                progressLock.withLock {
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
                                    sinceCheckpoint++
                                    if (sinceCheckpoint >= CHECKPOINT_SONGS || System.currentTimeMillis() - checkpointAt > CHECKPOINT_MS) {
                                        sinceCheckpoint = 0
                                        checkpointAt = System.currentTimeMillis()
                                        val partial = manifestSoFar()
                                        // Keep entries the plan already had (moved/kept) and add the written ones.
                                        manifest = partial.copy(songs = manifest.songs + partial.songs)
                                        runCatching { saveManifest() }
                                    }
                                }
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
                // Songs whose file could not be written stay out of the manifest (next sync tries again).
                val written = manifestSoFar().songs
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
        @Volatile private var notifyStartedAt = 0L

        private fun notify(done: Int, total: Int) {
            if (total < 2) return
            if (done == 0) notifyStartedAt = System.currentTimeMillis()
            val eta = moe.rukamori.archivetune.resona.ResonaEta.remaining(notifyStartedAt, done.toLong(), total.toLong())
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            runCatching { manager.notify(NOTIFICATION_ID, notification(done, total, eta)) }
        }

        /** Also the foreground worker's notification (same id, so progress updates replace it). */
        fun notification(done: Int, total: Int, eta: Long? = null): android.app.Notification =
                NotificationCompat.Builder(context, moe.rukamori.archivetune.playback.ExoDownloadService.CHANNEL_ID)
                    .setSmallIcon(moe.rukamori.archivetune.R.drawable.ic_download)
                    .setContentTitle(context.getString(moe.rukamori.archivetune.R.string.resona_files_notification))
                    .setContentText(
                        context.getString(moe.rukamori.archivetune.R.string.resona_files_count, done, total) +
                            (eta?.let { " · " + moe.rukamori.archivetune.resona.ResonaEta.text(context, it) } ?: ""),
                    )
                    .setProgress(total, done, total == 0)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()

        /** Title, artist, album, track number and a JPEG cover (≤ 600 px) – as the iPad writes them. */
        suspend fun tagsFor(songId: String): Mp4Tags.Tags {
            val song = database.getSongById(songId)
            val url = song?.song?.thumbnailUrl
            val source = url?.let { artwork.localArtwork(it) }?.takeIf { it.length() in 1..5_000_000 }?.readBytes()
                ?: url?.let { fetchCover(it) }
            return Mp4Tags.Tags(
                title = song?.song?.title ?: songId,
                artist = song?.artists?.joinToString(", ") { it.name }.orEmpty(),
                album = song?.song?.albumName,
                track = song?.song?.trackNumber,
                cover = CoverArt.jpeg(source),
            )
        }

        private fun fetchCover(url: String): ByteArray? =
            runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                try {
                    if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

        /**
         * Files written in an older format (the PS5 refused MediaMuxer's) are written
         * again from themselves: AAC frames copied, tags and cover new. No download.
         */
        private suspend fun upgradeFormats() {
            val old = manifest.songs.filterValues { it.format < ResonaFileFormat.CURRENT }
            if (old.isEmpty()) return
            val work = File(context.cacheDir, "resona-files").apply { mkdirs() }
            var done = 0
            val updated = HashMap(manifest.songs)
            for ((id, song) in old) {
                val file = resolve(song.path) ?: continue
                val source = File(work, "$id.old")
                val fresh = File(work, "$id.m4a")
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input -> source.outputStream().use { input.copyTo(it) } } ?: continue
                    ResonaAudioConverter.toM4a(source, fresh, tagsFor(id))
                    context.contentResolver.openOutputStream(file.uri, "wt")?.use { out -> fresh.inputStream().use { it.copyTo(out) } } ?: continue
                    updated[id] = song.copy(format = ResonaFileFormat.CURRENT, size = fresh.length())
                    done++
                    _progress.value = Progress(done, old.size, 0)
                    notify(done, old.size)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "format upgrade failed: ${e.javaClass.simpleName}")
                } finally {
                    source.delete()
                    fresh.delete()
                }
            }
            manifest = manifest.copy(songs = updated)
            uris.clear()
            saveManifest()
            _progress.value = null
            if (old.size > 1) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            Log.i(TAG, "format upgrade: $done of ${old.size} files written again")
        }

        /** Before files are copied elsewhere (the stick): bring old files to the current format. */
        suspend fun upgradeNow() = mutex.withLock { upgradeFormats() }

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
                val t0 = System.nanoTime()
                if (!copyFromCache(songId, raw)) return 0
                val t1 = System.nanoTime()
                val how = ResonaAudioConverter.toM4a(raw, m4a, tagsFor(songId))
                val t2 = System.nanoTime()
                val folder = folder(path.substringBefore('/')) ?: return 0
                val name = path.substringAfter('/')
                folder.findFile(name)?.delete()
                val target = createChild(folder, "audio/mp4", name) ?: return 0
                context.contentResolver.openOutputStream(target.uri)?.use { out -> m4a.inputStream().use { it.copyTo(out, COPY_BUFFER) } }
                val t3 = System.nanoTime()
                // Numbers only (round 12 measurement): cache copy, convert (remux or transcode), write.
                Log.i(
                    TAG,
                    "export %s: %.1f MB source → %.1f MB, copy %.1f s, convert %.1f s (%s), write %.1f s".format(
                        java.util.Locale.ROOT, songId, raw.length() / 1048576.0, m4a.length() / 1048576.0,
                        (t1 - t0) / 1e9, (t2 - t1) / 1e9, how, (t3 - t2) / 1e9,
                    ),
                )
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
                    val buffer = ByteArray(COPY_BUFFER)
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
            private const val COPY_BUFFER = 1024 * 1024
            private const val EXPORT_PARALLEL = 3
            private const val CHECKPOINT_SONGS = 10
            private const val CHECKPOINT_MS = 30_000L
            private const val PREFS = "resona_files"
            private const val KEY_ENABLED = "enabled"
            private const val KEY_TREE = "tree"
            private const val KEY_DEFAULT = "default_root"
            const val MANIFEST = ".resona-manifest.json"
            const val PLAYLIST_FILE = "playlist.m3u8"
            const val NOTIFICATION_ID = 0x5E50
        }
    }

/**
 * "Downloads as files" as a foreground worker (dataSync): full CPU while the screen is off,
 * the notification shows progress and the remaining time. Repeated requests are appended,
 * so a sync started while one runs is not lost.
 */
class FilesExportWorker(
    context: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(context, params) {
    private val files get() =
        dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, StickEntryPoint::class.java).downloadFiles()

    private fun info(): androidx.work.ForegroundInfo {
        val notification = files.notification(0, 0)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(ResonaDownloadFiles.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(ResonaDownloadFiles.NOTIFICATION_ID, notification)
        }
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo = info()

    override suspend fun doWork(): Result {
        // Not allowed from the background on some versions: then it runs as before.
        runCatching { setForeground(info()) }
        files.runQueued()
        return Result.success()
    }

    companion object {
        private const val WORK = "resona-files-export"

        fun enqueue(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<FilesExportWorker>().build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
