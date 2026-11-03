/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.resona.ResonaLyricsText
import moe.rukamori.archivetune.utils.rememberEnumPreference
import java.util.Locale

val ResonaAISyncModelKey = stringPreferencesKey("resona_ai_sync_model")

/** Lyrics → (plain lines, timed lines when the lyrics are synced). */
object AISyncInput {
    fun lines(lyrics: String): List<String> = ResonaLyricsText.lines(lyrics)

    fun synced(lyrics: String): List<AlignedLine> {
        val lrc = ResonaLyricsText.editable(lyrics)
        if (!lrc.trimStart().startsWith("[")) return emptyList()
        return runCatching { LyricsUtils.parseLyrics(lrc) }.getOrNull().orEmpty()
            .filter { it.text.isNotBlank() }
            .map { AlignedLine(it.text.trim(), it.time / 1000.0) }
    }
}

/**
 * "Sync with AI" — same flow and texts as the iPad's AISyncSheet:
 * calibrate synced lyrics, else sync word by word; on a low match offer
 * partial sync (remix), lyrics from the audio, or other lyrics.
 */
@Composable
fun ResonaAISyncDialog(
    lyrics: String,
    mediaId: String,
    durationSeconds: Int,
    onSave: (String) -> Unit,
    onSearchOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { ResonaAISync(context.applicationContext) }
    val phase by engine.phase.collectAsState()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var model by rememberEnumPreference(ResonaAISyncModelKey, WhisperModel.FAST)
    var modelRevision by remember { mutableIntStateOf(0) }
    val modelDownloaded = remember(model, modelRevision, phase) { model.isDownloaded(context) }
    val texts = remember(lyrics) { AISyncInput.lines(lyrics) }
    val synced = remember(lyrics) { AISyncInput.synced(lyrics) }
    val isSynced = synced.isNotEmpty()
    val running = phase is ResonaAISync.Phase.DownloadingModel || phase is ResonaAISync.Phase.PreparingAudio ||
        phase is ResonaAISync.Phase.Transcribing || phase is ResonaAISync.Phase.Aligning

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun start(mode: ResonaAISync.Mode) {
        job?.cancel()
        job = scope.launch { engine.run(mediaId, texts, synced, durationSeconds.toDouble(), mode, model) }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !running),
        title = { Text(stringResource(R.string.resona_ai_sync)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val p = phase) {
                    ResonaAISync.Phase.Idle -> {
                        Text(
                            stringResource(if (isSynced) R.string.resona_ai_desc_synced else R.string.resona_ai_desc_plain),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column {
                            WhisperModel.entries.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().selectable(option == model, role = Role.RadioButton) { model = option },
                                ) {
                                    RadioButton(selected = option == model, onClick = null)
                                    Text(
                                        stringResource(if (option == WhisperModel.FAST) R.string.resona_ai_model_fast else R.string.resona_ai_model_accurate),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        if (!modelDownloaded) {
                            Text(
                                stringResource(if (WhisperModels.onUnmeteredNetwork(context)) R.string.resona_ai_wifi else R.string.resona_ai_not_wifi),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(onClick = {
                                WhisperModels.delete(context, model)
                                modelRevision++
                            }) { Text(stringResource(R.string.resona_ai_delete_model)) }
                        }
                    }
                    is ResonaAISync.Phase.DownloadingModel -> Progress(stringResource(R.string.resona_ai_downloading), p.fraction)
                    ResonaAISync.Phase.PreparingAudio -> Progress(stringResource(R.string.resona_ai_preparing), null)
                    is ResonaAISync.Phase.Transcribing -> Progress(stringResource(R.string.resona_ai_listening), p.fraction)
                    ResonaAISync.Phase.Aligning -> Progress(stringResource(R.string.resona_ai_matching), null)
                    is ResonaAISync.Phase.Done ->
                        Text(stringResource(R.string.resona_ai_done, (p.matched * 100).toInt()), color = MaterialTheme.colorScheme.primary)
                    is ResonaAISync.Phase.Calibrated ->
                        Text(
                            stringResource(
                                R.string.resona_ai_calibrated,
                                String.format(Locale.ROOT, "%.3f×", p.calibration.factor),
                                String.format(Locale.ROOT, "%+.1f s", p.calibration.offset),
                                p.calibration.anchors,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    is ResonaAISync.Phase.CalibrationFailed -> {
                        Text(stringResource(R.string.resona_ai_calibration_failed, p.anchors))
                        OutlinedButton(onClick = { start(ResonaAISync.Mode.FULL_SYNC) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.resona_ai_word_by_word))
                        }
                    }
                    is ResonaAISync.Phase.LowMatch -> {
                        Text(stringResource(R.string.resona_ai_low_match, (p.ratio * 100).toInt()))
                        if (p.partialLines >= 2) {
                            OutlinedButton(onClick = { engine.partialSync() }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.resona_ai_partial))
                            }
                        }
                        OutlinedButton(onClick = { engine.lyricsFromAudio() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.resona_ai_from_audio))
                        }
                        OutlinedButton(onClick = { onDismiss(); onSearchOther() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.resona_ai_search_other))
                        }
                    }
                    is ResonaAISync.Phase.Failed ->
                        Text(
                            when (p.reason) {
                                ResonaAISync.NOT_CACHED -> stringResource(R.string.resona_ai_not_cached)
                                ResonaAISync.UNAVAILABLE -> stringResource(R.string.resona_ai_unavailable)
                                ResonaAISync.NO_LYRICS -> stringResource(R.string.resona_ai_no_lyrics)
                                else -> stringResource(R.string.resona_ai_failed, p.reason)
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                ResonaAISync.Phase.Idle ->
                    Button(onClick = { start(ResonaAISync.Mode.AUTO) }, enabled = texts.size >= 2) {
                        Text(stringResource(if (isSynced) R.string.resona_ai_calibrate else R.string.resona_ai_start))
                    }
                is ResonaAISync.Phase.Done -> Button(onClick = { onSave(p.lrc); onDismiss() }) { Text(stringResource(R.string.resona_ai_save)) }
                is ResonaAISync.Phase.Calibrated -> Button(onClick = { onSave(p.lrc); onDismiss() }) { Text(stringResource(R.string.resona_ai_save)) }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }) { Text(stringResource(if (running) R.string.cancel else R.string.close)) }
        },
    )
}

@Composable
private fun Progress(title: String, fraction: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${(fraction * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
