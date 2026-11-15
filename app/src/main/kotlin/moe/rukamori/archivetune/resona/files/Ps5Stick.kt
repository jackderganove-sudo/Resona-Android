/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.exoplayer.offline.Download
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads

/**
 * "PS5 stick" (round 12, point 4): chosen playlists are mirrored to a USB
 * stick as Music/<Playlist>/<Nr> - <Artist> - <Title>.m4a (exFAT/FAT32, the
 * PS5 media player reads AAC), copied from the downloads-as-files folder, with
 * the same manifest format (Music/.resona-sync.json). Unchanged files are not
 * written again, files the manifest doesn't know are never touched, the stick
 * gets no .nomedia.
 */
object Ps5Stick {
    private const val TAG = "ResonaStick"
    private const val PREFS = "resona_stick"
    private const val KEY_TREE = "tree"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_FLAT = "flat"
    private const val KEY_FORMAT = "format"
    // v2: the PS5 shows no covers for USB music in any variant (test 15.11.) → off by default;
    // a new key so the test folders go away on the next sync for everyone.
    private const val KEY_COVER_TEST = "cover_test_v2"
    const val COVER_TEST = "Cover-Test"
    const val COVER_TEST_B = "Cover-Test b folder.jpg"
    const val COVER_TEST_C = "Cover-Test c cover.jpg"
    const val MANIFEST = ".resona-sync.json"
    const val MUSIC = "Music"
    const val CHANNEL = "resona_stick"
    private const val NOTIFY_ASK = 0x5E51
    const val NOTIFY_PROGRESS = 0x5E52
    const val ACTION_SYNC = "dev.jackderganove.resona.STICK_SYNC"
    private const val WORK = "resona_stick_sync"
    const val KEY_ALLOW_DROP = "allow_drop"

    data class Progress(val done: Int, val total: Int, val bytes: Long, val totalBytes: Long, val phase: Phase)

    enum class Phase { LOADING_MISSING, COPYING, DONE, CANCELLED, FAILED, NO_SPACE }

    data class Preview(
        val steps: List<FileStep>,
        val manifest: ResonaManifest,
        val missing: List<String>,
        val bytesNeeded: Long,
        val freeBytes: Long?,
        val droppedFolders: List<String>,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tree(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    fun setTree(context: Context, uri: Uri?) = prefs(context).edit().putString(KEY_TREE, uri?.toString()).apply()

    fun playlists(context: Context): Set<String> = prefs(context).getStringSet(KEY_PLAYLISTS, emptySet()).orEmpty()

    fun setPlaylists(context: Context, ids: Set<String>) = prefs(context).edit().putStringSet(KEY_PLAYLISTS, ids).apply()

    fun flat(context: Context): Boolean = prefs(context).getBoolean(KEY_FLAT, false)

    fun setFlat(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_FLAT, value).apply()

    /** "m4a" (AAC, default) or "mp3" (320 kbit/s CBR, ID3v2.3). */
    fun format(context: Context): String = prefs(context).getString(KEY_FORMAT, "m4a") ?: "m4a"

    fun setFormat(context: Context, value: String) = prefs(context).edit().putString(KEY_FORMAT, value).apply()

    /** Extra folders with one song in several cover variants (until the PS5 test says which works). */
    fun coverTest(context: Context): Boolean = prefs(context).getBoolean(KEY_COVER_TEST, false)

    fun setCoverTest(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_COVER_TEST, value).apply()

    fun stickName(context: Context): String? = tree(context)?.let { DocumentFile.fromTreeUri(context, it)?.name }

    private fun music(context: Context, create: Boolean): DocumentFile? {
        val root = tree(context)?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        if (!root.canWrite()) return null
        return root.findFile(MUSIC)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(MUSIC) else null
    }

    private fun resolve(base: DocumentFile, path: String): DocumentFile? {
        var at = base
        for (part in path.split('/').filter { it.isNotEmpty() }) at = at.findFile(part) ?: return null
        return at
    }

    /** Free space of the stick: its volume from the tree's document id ("1234-ABCD:…"). */
    fun freeBytes(context: Context): Long? {
        val tree = tree(context) ?: return null
        val volume = runCatching { DocumentsContract.getTreeDocumentId(tree).substringBefore(':') }.getOrNull() ?: return null
        if (volume.isBlank() || volume == "primary") return null
        return runCatching { StatFs("/storage/$volume").availableBytes }.getOrNull()
    }

    private suspend fun wanted(context: Context, database: MusicDatabase): List<WantedPlaylist> {
        val selected = playlists(context)
        val playlists = database.allPlaylistsForDownloads().first().filter { it.playlist.id in selected }
        val maps = database.allPlaylistSongMapsForDownloads().first().groupBy { it.playlistId }
        return playlists.map { p ->
            WantedPlaylist(p.playlist.id, p.playlist.name, maps[p.playlist.id].orEmpty().sortedBy { it.position }.map { it.songId })
        }
    }

    private suspend fun oldManifest(context: Context): ResonaManifest {
        val music = music(context, create = false) ?: return ResonaManifest()
        val file = music.findFile(MANIFEST) ?: return ResonaManifest()
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { ResonaManifest.decode(it.readBytes().decodeToString()) }
        }.getOrNull() ?: ResonaManifest()
    }

    /** What a sync would do (shown before; dropped playlist folders are asked about). */
    suspend fun preview(context: Context): Preview? {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, StickEntryPoint::class.java)
        if (music(context, create = false) == null && tree(context) == null) return null
        val database = deps.database()
        val files = deps.downloadFiles()
        val playlists = wanted(context, database)
        val infos = HashMap<String, SongInfo>()
        playlists.flatMap { it.songIds }.distinct().forEach { id ->
            database.getSongById(id)?.let { infos[id] = SongInfo(id, it.song.title, it.artists.joinToString(", ") { a -> a.name }) }
        }
        val old = oldManifest(context)
        val (planned, manifest) = ResonaFilePlan.plan(old, playlists, emptyList(), { infos[it] }, copiesPerPlaylist = true, flat = flat(context), extension = format(context))
        // Files in an older format (MediaMuxer's, which the PS5 refused) are copied again in place.
        val touched = planned.filterIsInstance<FileStep.Add>().map { it.path }.toSet() + planned.filterIsInstance<FileStep.Move>().map { it.to }.toSet()
        val rewrite = manifest.songs.filter { (key, song) -> (old.songs[key]?.format ?: ResonaFileFormat.CURRENT) < ResonaFileFormat.CURRENT && song.path !in touched }
            .map { (key, song) -> FileStep.Add(key.substringAfter('/'), song.path) }
        val steps = planned + rewrite
        val adds = steps.filterIsInstance<FileStep.Add>()
        val missing = adds.map { it.songId }.distinct().filter { files.uriFor(it) == null }
        val bytes = adds.sumOf { add -> files.uriFor(add.songId)?.let { DocumentFile.fromSingleUri(context, it)?.length() } ?: AVERAGE_SONG_BYTES }
        return Preview(steps, manifest, missing, bytes, freeBytes(context), steps.filterIsInstance<FileStep.DeleteFolder>().map { it.folder })
    }

    private const val AVERAGE_SONG_BYTES = 8L * 1024 * 1024

    /** The sync itself (worker). Missing songs are downloaded and converted first. */
    suspend fun run(context: Context, allowDrop: Boolean, onProgress: (Progress) -> Unit = {}): Phase {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, StickEntryPoint::class.java)
        val files = deps.downloadFiles()
        // The source files first in the current format (old ones are written again).
        runCatching { files.upgradeNow() }
        var preview = preview(context) ?: return Phase.FAILED
        if (preview.missing.isNotEmpty()) {
            val all = deps.downloadUtil().downloads.value
            val titles = preview.missing.associateWith { id -> deps.database().getSongById(id)?.song?.title.orEmpty() }
            sendAddMissingDownloads(context, preview.missing.map { HeaderDownloadItem(id = it, title = titles[it].orEmpty()) }, all)
            // Wait until they are files (or give up after a while per song).
            val deadline = System.currentTimeMillis() + preview.missing.size * 90_000L
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val left = preview.missing.count { files.uriFor(it) == null }
                val p = Progress(preview.missing.size - left, preview.missing.size, 0, 0, Phase.LOADING_MISSING)
                _progress.value = p
                onProgress(p)
                if (left == 0) break
                val failed = deps.downloadUtil().downloads.value.filterKeys { it in preview.missing }.values.count { it.state == Download.STATE_FAILED }
                if (failed >= left) break
                delay(3_000)
            }
            preview = preview(context) ?: return Phase.FAILED
        }
        val free = preview.freeBytes
        if (free != null && preview.bytesNeeded > free) {
            _progress.value = Progress(0, 0, 0, preview.bytesNeeded, Phase.NO_SPACE)
            return Phase.NO_SPACE
        }
        val music = music(context, create = true) ?: return Phase.FAILED
        // Deselected playlists' folders only go after a confirmation (settings); otherwise they stay as they are.
        if (!allowDrop && preview.droppedFolders.isNotEmpty()) {
            val old = oldManifest(context)
            val kept = old.songs.filterValues { it.path.substringBefore('/') in preview.droppedFolders }
            val keptFolders = old.folders.filterValues { it in preview.droppedFolders }
            preview =
                preview.copy(
                    steps = preview.steps.filter { it !is FileStep.DeleteFolder },
                    manifest = preview.manifest.copy(songs = preview.manifest.songs + kept, folders = preview.manifest.folders + keptFolders),
                )
        }
        val adds = preview.steps.count { it is FileStep.Add }
        var done = 0
        var bytes = 0L
        val written = HashSet<String>()
        try {
            for (step in preview.steps) {
                currentCoroutineContext().ensureActive()
                runCatching {
                    when (step) {
                        is FileStep.RenameFolder -> resolve(music, step.from)?.renameTo(step.to)
                        is FileStep.DeleteFolder -> {
                            val dir = resolve(music, step.folder)
                            oldManifest(context).songs.values.filter { it.path.substringBefore('/') == step.folder }.forEach { resolve(music, it.path)?.delete() }
                            if (dir != null && dir.listFiles().isEmpty()) dir.delete()
                        }
                        is FileStep.Delete -> resolve(music, step.path)?.delete()
                        is FileStep.Move -> move(context, music, step.from, step.to)
                        is FileStep.Add -> {
                            val source = files.uriFor(step.songId)
                            if (source != null) {
                                bytes += if (step.path.endsWith(".mp3")) copyAsMp3(context, music, source, step.path, files.tagsFor(step.songId)) else copy(context, music, source, step.path)
                                written += step.path
                            }
                            done++
                            val p = Progress(done, adds, bytes, preview.bytesNeeded, Phase.COPYING)
                            _progress.value = p
                            onProgress(p)
                        }
                    }
                }.onFailure { Log.w(TAG, "step ${step.javaClass.simpleName} failed: ${it.javaClass.simpleName}") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            saveManifest(context, music, preview.manifest, written, preview.steps)
            _progress.value = Progress(done, adds, bytes, preview.bytesNeeded, Phase.CANCELLED)
            throw e
        }
        saveManifest(context, music, preview.manifest, written, preview.steps)
        runCatching { coverTestFolders(context, music, files) }.onFailure { Log.w(TAG, "cover test: ${it.javaClass.simpleName}") }
        _progress.value = Progress(done, adds, bytes, preview.bytesNeeded, Phase.DONE)
        return Phase.DONE
    }

    /** Only songs really on the stick go into its manifest (a cancelled run resumes). */
    private fun saveManifest(context: Context, music: DocumentFile, planned: ResonaManifest, written: Set<String>, steps: List<FileStep>) {
        val adds = steps.filterIsInstance<FileStep.Add>().map { it.path }.toSet()
        val songs = planned.songs.filterValues { it.path !in adds || it.path in written }
            .mapValues { (_, song) -> if (song.path in written) song.copy(format = ResonaFileFormat.CURRENT) else song }
        val text = planned.copy(songs = songs).encode()
        val existing = music.findFile(MANIFEST)
        // A sync without changes writes nothing on the stick, not even the manifest.
        if (existing != null) {
            val old = runCatching { context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (old == text) return
        }
        val file = existing ?: music.createFile("application/json", MANIFEST) ?: return
        runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
        }
    }

    private suspend fun copyAsMp3(context: Context, music: DocumentFile, source: Uri, path: String, tags: Mp4Tags.Tags): Long {
        val work = java.io.File(context.cacheDir, "resona-stick").apply { mkdirs() }
        val m4a = java.io.File(work, "source.m4a")
        val mp3 = java.io.File(work, "song.mp3")
        try {
            context.contentResolver.openInputStream(source)?.use { input -> m4a.outputStream().use { input.copyTo(it) } } ?: return 0
            StickFormats.mp3(m4a, mp3, tags)
            return copyFile(context, music, mp3, path, "audio/mpeg")
        } finally {
            m4a.delete()
            mp3.delete()
        }
    }

    private fun copyFile(context: Context, music: DocumentFile, file: java.io.File, path: String, mime: String): Long {
        val folder = folderFor(music, path) ?: return 0
        val name = path.substringAfterLast('/')
        folder.findFile(name)?.delete()
        val target = folder.createFile(mime, name) ?: return 0
        if (target.name != name) target.renameTo(name)
        context.contentResolver.openOutputStream(target.uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
        return file.length()
    }

    /**
     * Cover test (round 12): one song in five variants, to see on the PS5 which
     * one shows a cover. Cover-Test/: a) M4A covr, d) MP3 ID3v2.3 APIC, e) FLAC
     * PICTURE; "Cover-Test b folder.jpg/" and "Cover-Test c cover.jpg/": M4A
     * without an embedded cover plus the folder image. Switched off → only
     * these files are removed.
     */
    private suspend fun coverTestFolders(context: Context, music: DocumentFile, files: ResonaDownloadFiles) {
        val names = mapOf(
            COVER_TEST to listOf("a - M4A mit Cover im Tag (covr).m4a", "d - MP3 mit Cover im Tag (ID3v2.3 APIC).mp3", "e - FLAC mit Cover im Tag (PICTURE).flac"),
            COVER_TEST_B to listOf("b - M4A ohne Cover-Tag, Bild als folder.jpg.m4a", "folder.jpg"),
            COVER_TEST_C to listOf("c - M4A ohne Cover-Tag, Bild als cover.jpg.m4a", "cover.jpg"),
        )
        if (!coverTest(context)) {
            for ((folder, list) in names) {
                val dir = music.findFile(folder) ?: continue
                list.forEach { dir.findFile(it)?.delete() }
                if (dir.listFiles().isEmpty()) dir.delete()
            }
            return
        }
        if (names.all { (folder, list) -> music.findFile(folder)?.let { dir -> list.all { dir.findFile(it) != null } } == true }) return
        val stickManifest = oldManifest(context)
        val songId = playlists(context).asSequence()
            .mapNotNull { stickManifest.playlists[it]?.firstOrNull { id -> files.uriFor(id) != null } }
            .firstOrNull() ?: return
        val source = files.uriFor(songId) ?: return
        val tags = files.tagsFor(songId)
        val cover = tags.cover ?: return
        val work = java.io.File(context.cacheDir, "resona-cover-test").apply { mkdirs() }
        try {
            val m4a = java.io.File(work, "source.m4a")
            context.contentResolver.openInputStream(source)?.use { input -> m4a.outputStream().use { input.copyTo(it) } } ?: return
            val a = java.io.File(work, "a.m4a").also { ResonaAudioConverter.toM4a(m4a, it, tags) }
            val plain = java.io.File(work, "plain.m4a").also { StickFormats.m4aWithoutCover(m4a, it, tags) }
            val jpg = java.io.File(work, "cover.jpg").also { it.writeBytes(cover) }
            copyFile(context, music, a, "$COVER_TEST/${names.getValue(COVER_TEST)[0]}", "audio/mp4")
            if (Mp3Native.available) {
                val d = java.io.File(work, "d.mp3").also { StickFormats.mp3(m4a, it, tags) }
                copyFile(context, music, d, "$COVER_TEST/${names.getValue(COVER_TEST)[1]}", "audio/mpeg")
            }
            val e = java.io.File(work, "e.flac").also { StickFormats.flac(m4a, it, tags) }
            copyFile(context, music, e, "$COVER_TEST/${names.getValue(COVER_TEST)[2]}", "audio/flac")
            copyFile(context, music, plain, "$COVER_TEST_B/${names.getValue(COVER_TEST_B)[0]}", "audio/mp4")
            copyFile(context, music, jpg, "$COVER_TEST_B/folder.jpg", "image/jpeg")
            copyFile(context, music, plain, "$COVER_TEST_C/${names.getValue(COVER_TEST_C)[0]}", "audio/mp4")
            copyFile(context, music, jpg, "$COVER_TEST_C/cover.jpg", "image/jpeg")
            Log.i(TAG, "cover test folders written")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun folderFor(music: DocumentFile, path: String): DocumentFile? {
        if ('/' !in path) return music
        val name = path.substringBefore('/')
        return music.findFile(name)?.takeIf { it.isDirectory } ?: music.createDirectory(name)
    }

    private fun copy(context: Context, music: DocumentFile, source: Uri, path: String): Long {
        val folder = folderFor(music, path) ?: return 0
        val name = path.substringAfterLast('/')
        folder.findFile(name)?.delete()
        val target = folder.createFile("audio/mp4", name) ?: return 0
        if (target.name != name) target.renameTo(name)
        var count = 0L
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { out -> count = input.copyTo(out, 256 * 1024) }
        }
        return count
    }

    private fun move(context: Context, music: DocumentFile, from: String, to: String) {
        val source = resolve(music, from) ?: return
        val sameFolder = from.substringBeforeLast('/', "") == to.substringBeforeLast('/', "")
        if (sameFolder) {
            source.renameTo(to.substringAfterLast('/'))
            return
        }
        copy(context, music, source.uri, to)
        source.delete()
    }

    fun enqueue(context: Context, allowDrop: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<StickSyncWorker>().setInputData(workDataOf(KEY_ALLOW_DROP to allowDrop)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.resona_stick_title), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** "Sync PS5 stick?" when a stick with our folder is mounted. */
    @android.annotation.SuppressLint("MissingPermission")
    fun askToSync(context: Context) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val sync =
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, StickReceiver::class.java).setAction(ACTION_SYNC),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.resona_stick_ask))
                .setContentText(stickName(context).orEmpty())
                .addAction(0, context.getString(R.string.resona_stick_sync_now), sync)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(NOTIFY_ASK, notification) }
    }

    fun dismissAsk(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFY_ASK)

    /** Mounted volume ("file:///storage/1234-ABCD") is the stick we know. */
    fun isOurStick(context: Context, mounted: Uri?): Boolean {
        val tree = tree(context) ?: return false
        val volume = runCatching { DocumentsContract.getTreeDocumentId(tree).substringBefore(':') }.getOrNull() ?: return false
        return mounted?.path?.trimEnd('/')?.substringAfterLast('/') == volume
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StickEntryPoint {
    fun database(): MusicDatabase

    fun downloadUtil(): DownloadUtil

    fun downloadFiles(): ResonaDownloadFiles
}

class StickSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0, 0)

    private fun info(done: Int, total: Int, mb: Long): ForegroundInfo {
        Ps5Stick.ensureChannel(applicationContext)
        val cancel = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification =
            NotificationCompat.Builder(applicationContext, Ps5Stick.CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(applicationContext.getString(R.string.resona_stick_syncing))
                .setContentText(applicationContext.getString(R.string.resona_stick_progress, done, total, mb))
                .setProgress(total, done, total == 0)
                .setOngoing(true)
                .setSilent(true)
                .addAction(0, applicationContext.getString(R.string.cancel), cancel)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Ps5Stick.NOTIFY_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Ps5Stick.NOTIFY_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        Ps5Stick.dismissAsk(applicationContext)
        runCatching { setForeground(info(0, 0, 0)) }
        val phase =
            Ps5Stick.run(applicationContext, inputData.getBoolean(Ps5Stick.KEY_ALLOW_DROP, false)) { p ->
                runCatching { setForegroundAsync(info(p.done, p.total, p.bytes / (1024 * 1024))) }
            }
        notifyResult(phase)
        return if (phase == Ps5Stick.Phase.DONE) Result.success() else Result.failure()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyResult(phase: Ps5Stick.Phase) {
        val text =
            when (phase) {
                Ps5Stick.Phase.DONE -> R.string.resona_stick_done
                Ps5Stick.Phase.NO_SPACE -> R.string.resona_stick_no_space
                else -> R.string.resona_stick_failed
            }
        val notification =
            NotificationCompat.Builder(applicationContext, Ps5Stick.CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(applicationContext.getString(R.string.resona_stick_title))
                .setContentText(applicationContext.getString(text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(applicationContext.getString(text)))
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(Ps5Stick.NOTIFY_PROGRESS + 1, notification) }
    }
}

/** Stick mounted → ask; "Sync now" from the notification → worker. */
class StickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> if (Ps5Stick.isOurStick(context, intent.data)) Ps5Stick.askToSync(context)
            Ps5Stick.ACTION_SYNC -> Ps5Stick.enqueue(context)
        }
    }
}
