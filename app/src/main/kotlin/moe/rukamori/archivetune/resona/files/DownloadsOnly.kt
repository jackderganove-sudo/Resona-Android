/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.media3.exoplayer.offline.Download
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * "Downloads only" (round 12): one switch for playlists and search — lists
 * show only downloaded songs and search looks in the library, so nothing
 * needs the network (offline mode).
 */
val ResonaDownloadsOnlyKey = booleanPreferencesKey("resona_downloads_only")

@Composable
fun rememberDownloadsOnly(): Pair<Boolean, (Boolean) -> Unit> {
    val (value, set) = rememberPreference(ResonaDownloadsOnlyKey, false)
    return value to set
}

/** Ids of finished downloads (live). */
@Composable
fun rememberCompletedDownloads(): Set<String> {
    val util = LocalDownloadUtil.current
    val downloads by util.downloads.collectAsState()
    return remember(downloads) { downloads.filterValues { it.state == Download.STATE_COMPLETED }.keys }
}

@Composable
fun DownloadsOnlyChip(modifier: Modifier = Modifier) {
    val (on, set) = rememberDownloadsOnly()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilterChip(
            selected = on,
            onClick = { set(!on) },
            label = { Text(stringResource(R.string.resona_downloads_only)) },
            leadingIcon = { Icon(painterResource(R.drawable.offline), contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("downloadsOnly"),
        )
    }
}
