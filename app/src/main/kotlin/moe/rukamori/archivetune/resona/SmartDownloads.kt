/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.media3.exoplayer.offline.Download
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.getAsync
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import java.util.concurrent.TimeUnit

/**
 * Smart downloads (Resona, off by default): on unmetered networks keep the
 * most played songs of the last 30 days and the liked songs downloaded, up
 * to [MaxSongs]. Smart downloads are tracked separately; songs that drop
 * out are removed again. Songs the user downloaded themselves are never
 * touched.
 */
object SmartDownloads {
    val EnabledKey = booleanPreferencesKey("resona_smart_downloads")
    val SmartIdsKey = stringSetPreferencesKey("resona_smart_download_ids")
    const val MaxSongs = 150
    private const val WorkName = "resona_smart_downloads"

    data class Plan(val toDownload: List<String>, val toRemove: Set<String>, val smartIds: Set<String>)

    /**
     * [mostPlayed] and [liked] in priority order; [downloaded] = completed or
     * running downloads; [previousSmart] = what smart downloads added before.
     */
    fun plan(
        mostPlayed: List<String>,
        liked: List<String>,
        downloaded: Set<String>,
        previousSmart: Set<String>,
        maxSongs: Int = MaxSongs,
    ): Plan {
        val selection = (mostPlayed + liked).distinct().take(maxSongs)
        val selected = selection.toSet()
        val toDownload = selection.filter { it !in downloaded }
        // Only what smart downloads added itself may go again.
        val toRemove = previousSmart - selected
        val smartIds = (previousSmart intersect selected) + toDownload
        return Plan(toDownload, toRemove, smartIds)
    }

    fun schedule(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WorkName)
            return
        }
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
        val request =
            PeriodicWorkRequestBuilder<SmartDownloadsWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        workManager.enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    suspend fun scheduleFromSettings(context: Context) {
        schedule(context, context.dataStore.getAsync(EnabledKey) ?: false)
    }
}

class SmartDownloadsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dataStore = applicationContext.dataStore
        if (dataStore.getAsync(SmartDownloads.EnabledKey) != true) return Result.success()
        return try {
            val dependencies = EntryPointAccessors.fromApplication(applicationContext, SmartDownloadsEntryPoint::class.java)
            val database = dependencies.database()
            val downloads = dependencies.downloadUtil().downloads.value
            val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            val mostPlayed = database.mostPlayedSongsStats(fromTimeStamp = since, limit = SmartDownloads.MaxSongs).first()
            val liked = database.likedSongsByCreateDateAsc().first().reversed()
            val titles = mostPlayed.associate { it.id to it.title } + liked.associate { it.song.id to it.song.title }
            val downloaded = downloads.filterValues { it.state != Download.STATE_FAILED && it.state != Download.STATE_REMOVING }.keys
            val plan = SmartDownloads.plan(
                mostPlayed = mostPlayed.map { it.id },
                liked = liked.map { it.song.id },
                downloaded = downloaded,
                previousSmart = dataStore.getAsync(SmartDownloads.SmartIdsKey) ?: emptySet(),
            )
            sendAddMissingDownloads(
                context = applicationContext,
                songs = plan.toDownload.map { HeaderDownloadItem(id = it, title = titles[it].orEmpty()) },
                downloads = downloads,
            )
            if (plan.toRemove.isNotEmpty()) sendRemoveDownloads(applicationContext, plan.toRemove.toList())
            dataStore.edit { it[SmartDownloads.SmartIdsKey] = plan.smartIds }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            reportException(exception)
            Result.retry()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmartDownloadsEntryPoint {
    fun database(): MusicDatabase

    fun downloadUtil(): DownloadUtil
}

/** Switch for Settings → Storage. */
@Composable
fun ResonaSmartDownloadsPreference() {
    val context = LocalContext.current
    val (enabled, onEnabledChange) = rememberPreference(SmartDownloads.EnabledKey, defaultValue = false)
    SwitchPreference(
        title = { Text(stringResource(R.string.resona_smart_downloads)) },
        description = stringResource(R.string.resona_smart_downloads_desc, SmartDownloads.MaxSongs),
        icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
        checked = enabled,
        onCheckedChange = { value ->
            onEnabledChange(value)
            SmartDownloads.schedule(context, value)
        },
    )
}
