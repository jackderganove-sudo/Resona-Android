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
        val (planned, manifest) = ResonaFilePlan.plan(old, playlists, emptyList(), { infos[it] }, copiesPerPlaylist = true, flat = flat(context))
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
                                bytes += copy(context, music, source, step.path)
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
        _progress.value = Progress(done, adds, bytes, preview.bytesNeeded, Phase.DONE)
        return Phase.DONE
    }

    /** Only songs really on the stick go into its manifest (a cancelled run resumes). */
    private fun saveManifest(context: Context, music: DocumentFile, planned: ResonaManifest, written: Set<String>, steps: List<FileStep>) {
        val adds = steps.filterIsInstance<FileStep.Add>().map { it.path }.toSet()
        val songs = planned.songs.filterValues { it.path !in adds || it.path in written }
            .mapValues { (_, song) -> if (song.path in written) song.copy(format = ResonaFileFormat.CURRENT) else song }
        val file = music.findFile(MANIFEST) ?: music.createFile("application/json", MANIFEST) ?: return
        runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(planned.copy(songs = songs).encode().toByteArray()) }
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
