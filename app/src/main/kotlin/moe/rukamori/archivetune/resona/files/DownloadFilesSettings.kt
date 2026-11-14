/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadFilesEntryPoint {
    fun downloadFiles(): ResonaDownloadFiles
}

/** Settings → Storage: "Save downloads as files", folder, progress of the take-over. */
@Composable
fun DownloadFilesSettingsGroup() {
    val context = LocalContext.current
    val files = remember { EntryPointAccessors.fromApplication(context.applicationContext, DownloadFilesEntryPoint::class.java).downloadFiles() }
    var enabled by remember { mutableStateOf(files.enabled) }
    var folder by remember { mutableStateOf(files.folderLabel()) }
    val progress by files.progress.collectAsState()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                files.chosenFolder = uri
                folder = files.folderLabel()
            }
        }

    PreferenceGroup(title = stringResource(R.string.resona_files_title)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.resona_files_switch)) },
                description = stringResource(R.string.resona_files_switch_desc),
                icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    files.enabled = it
                },
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.resona_files_folder)) },
                description = folder,
                icon = { Icon(painterResource(R.drawable.snippet_folder), contentDescription = null) },
                onClick = { picker.launch(null) },
                isEnabled = enabled,
            )
        }
        progress?.let { p ->
            item {
                PreferenceEntry(
                    title = {
                        Text(stringResource(R.string.resona_files_progress, p.done, p.total, p.bytes / (1024 * 1024)))
                    },
                    description = null,
                    content = {
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    },
                )
            }
        }
    }
}
