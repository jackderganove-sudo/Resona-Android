/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.resona.AlertDialog
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference

/** Settings → Storage → "PS5 stick": stick, playlists, flat layout, sync with progress. */
@Composable
fun Ps5StickSettingsGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { EntryPointAccessors.fromApplication(context.applicationContext, StickEntryPoint::class.java).database() }
    val allPlaylists by database.playlistsByNameAsc().collectAsState(initial = emptyList())
    var stick by remember { mutableStateOf(Ps5Stick.stickName(context)) }
    var selected by remember { mutableStateOf(Ps5Stick.playlists(context)) }
    var flat by remember { mutableStateOf(Ps5Stick.flat(context)) }
    var mp3 by remember { mutableStateOf(Ps5Stick.format(context) == "mp3") }
    var coverTest by remember { mutableStateOf(Ps5Stick.coverTest(context)) }
    var choosing by remember { mutableStateOf(false) }
    var askDrop by remember { mutableStateOf<List<String>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val progress by Ps5Stick.progress.collectAsState()

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                Ps5Stick.setTree(context, uri)
                stick = Ps5Stick.stickName(context)
            }
        }

    fun startSync() {
        info = null
        scope.launch {
            val preview = Ps5Stick.preview(context)
            when {
                preview == null -> info = context.getString(R.string.resona_stick_choose_first)
                preview.freeBytes != null && preview.bytesNeeded > preview.freeBytes ->
                    info = context.getString(R.string.resona_stick_no_space)
                preview.droppedFolders.isNotEmpty() -> askDrop = preview.droppedFolders
                else -> Ps5Stick.enqueue(context)
            }
        }
    }

    PreferenceGroup(title = stringResource(R.string.resona_stick_title)) {
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.resona_stick_no_covers)) },
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.resona_stick_choose)) },
                description = stick ?: stringResource(R.string.resona_stick_none),
                icon = { Icon(painterResource(R.drawable.snippet_folder), contentDescription = null) },
                onClick = { picker.launch(null) },
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.resona_stick_playlists)) },
                description = stringResource(R.string.resona_stick_playlists_count, selected.size),
                icon = { Icon(painterResource(R.drawable.queue_music), contentDescription = null) },
                onClick = { choosing = true },
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.resona_stick_flat)) },
                description = stringResource(R.string.resona_stick_flat_desc),
                checked = flat,
                onCheckedChange = {
                    flat = it
                    Ps5Stick.setFlat(context, it)
                },
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.resona_stick_mp3)) },
                description = stringResource(R.string.resona_stick_mp3_desc),
                checked = mp3,
                onCheckedChange = {
                    mp3 = it
                    Ps5Stick.setFormat(context, if (it) "mp3" else "m4a")
                },
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.resona_stick_cover_test)) },
                description = stringResource(R.string.resona_stick_cover_test_desc),
                checked = coverTest,
                onCheckedChange = {
                    coverTest = it
                    Ps5Stick.setCoverTest(context, it)
                },
            )
        }
        item {
            val p = progress
            val running = p != null && (p.phase == Ps5Stick.Phase.COPYING || p.phase == Ps5Stick.Phase.LOADING_MISSING)
            PreferenceEntry(
                title = { Text(stringResource(if (running) R.string.resona_stick_syncing else R.string.resona_stick_sync_now)) },
                description =
                    when {
                        p == null -> info ?: stringResource(R.string.resona_stick_sync_desc)
                        p.phase == Ps5Stick.Phase.LOADING_MISSING -> stringResource(R.string.resona_stick_loading, p.done, p.total)
                        p.phase == Ps5Stick.Phase.COPYING -> stringResource(R.string.resona_stick_progress, p.done, p.total, p.bytes / (1024 * 1024))
                        p.phase == Ps5Stick.Phase.DONE -> stringResource(R.string.resona_stick_done)
                        p.phase == Ps5Stick.Phase.NO_SPACE -> stringResource(R.string.resona_stick_no_space)
                        p.phase == Ps5Stick.Phase.CANCELLED -> stringResource(R.string.resona_stick_cancelled)
                        else -> stringResource(R.string.resona_stick_failed)
                    },
                icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                content =
                    if (running) {
                        {
                            Column {
                                LinearProgressIndicator(
                                    progress = { if (p!!.total == 0) 0f else p.done.toFloat() / p.total },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                TextButton(onClick = { Ps5Stick.cancel(context) }) { Text(stringResource(R.string.cancel)) }
                            }
                        }
                    } else {
                        null
                    },
                onClick = if (running) null else ({ startSync() }),
                isEnabled = stick != null && selected.isNotEmpty(),
            )
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.resona_stick_playlists)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(allPlaylists, key = { it.playlist.id }) { p ->
                        val on = p.playlist.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (on) selected - p.playlist.id else selected + p.playlist.id
                            },
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(p.playlist.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Ps5Stick.setPlaylists(context, selected)
                    choosing = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }

    askDrop?.let { folders ->
        AlertDialog(
            onDismissRequest = { askDrop = null },
            title = { Text(stringResource(R.string.resona_stick_drop_title)) },
            text = { Text(stringResource(R.string.resona_stick_drop_text, folders.joinToString(", "))) },
            confirmButton = {
                TextButton(onClick = {
                    askDrop = null
                    Ps5Stick.enqueue(context, allowDrop = true)
                }) { Text(stringResource(R.string.resona_stick_drop_yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDrop = null
                    Ps5Stick.enqueue(context, allowDrop = false)
                }) { Text(stringResource(R.string.resona_stick_drop_keep)) }
            },
        )
    }
}
