/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import java.util.Locale

/**
 * Self-sync (Resona): for lyrics without timing, tap once at the start of
 * every line while the song plays; the result is saved as LRC.
 */
object TapSync {
    /** Text lines without timestamps, empty lines dropped. */
    fun lines(lyrics: String): List<String> =
        LyricsTempo.stripTimestamps(lyrics).lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun buildLrc(lines: List<String>, timesMs: List<Long>): String =
        lines.indices.filter { it < timesMs.size }.joinToString("\n") { index ->
            val centis = (timesMs[index].coerceAtLeast(0) + 5) / 10
            String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", centis / 6000, centis / 100 % 60, centis % 100, lines[index])
        }
}

@Composable
fun ResonaTapSyncDialog(
    lyrics: String,
    positionMs: () -> Long,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val lines = remember(lyrics) { TapSync.lines(lyrics) }
    val times = remember(lyrics) { mutableStateListOf<Long>() }
    val index = times.size
    val done = index >= lines.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resona_tap_sync)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.resona_tap_sync_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (done) stringResource(R.string.resona_tap_sync_done) else lines[index],
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!done && index + 1 < lines.size) {
                    Text(
                        text = lines[index + 1],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "${minOf(index, lines.size)} / ${lines.size}",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { if (times.isNotEmpty()) times.removeAt(times.lastIndex) }, enabled = times.isNotEmpty()) {
                        Text(stringResource(R.string.resona_tap_sync_undo))
                    }
                    Button(
                        onClick = { if (!done) times.add(positionMs()) },
                        enabled = !done,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Text(stringResource(R.string.resona_tap_sync_tap))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(TapSync.buildLrc(lines, times)); onDismiss() }, enabled = times.isNotEmpty()) {
                Text(stringResource(R.string.resona_tap_sync_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
