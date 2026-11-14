/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R

/** Where the music plays now; follows Android's output changes while shown. */
@Composable
fun rememberAudioOutput(): AudioOutput {
    val context = LocalContext.current
    val watcher = remember(context) { AudioOutputWatcher(context.applicationContext) }
    DisposableEffect(watcher) {
        watcher.start()
        onDispose { watcher.stop() }
    }
    val output by watcher.current.collectAsState()
    return output
}

@Composable
fun AudioOutput.label(): String = if (!isExternal || name.isBlank()) stringResource(R.string.resona_speakers_this_device) else name

/**
 * Player button (bottom row, between sleep timer and lyrics — same place as
 * on the iPad): a circle on this device, a pill with icon + name on an
 * external output. Always 48 dp high.
 */
@Composable
fun PlayerSpeakerButton(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val output = rememberAudioOutput()
    val external = output.isExternal
    Box(
        modifier =
            modifier
                .height(48.dp)
                .widthIn(min = 48.dp)
                .clip(if (external) RoundedCornerShape(24.dp) else CircleShape)
                .background(color.copy(alpha = if (external) 0.2f else 0.1f))
                .clickable(onClick = onClick)
                .padding(horizontal = if (external) 14.dp else 0.dp)
                .testTag("player.speakers"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(output.icon), contentDescription = stringResource(R.string.resona_speakers), tint = color, modifier = Modifier.size(22.dp))
            if (external) {
                Spacer(Modifier.width(8.dp))
                Text(
                    output.label(),
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
    }
}

/** Mini player: icon + name, only for external outputs (as on the iPad). */
@Composable
fun CurrentOutputLabel(color: Color, modifier: Modifier = Modifier) {
    val output = rememberAudioOutput()
    if (!output.isExternal) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(painterResource(output.icon), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(output.label(), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
    }
}

private const val PREFS = "resona_speakers"
private const val ASKED = "asked_permission"

/** Content of the glass menu sheet: same order and texts as the iPad. */
@SuppressLint("MissingPermission")
@Composable
fun SpeakerSheetContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val output = rememberAudioOutput()
    var hasConnect by remember { mutableStateOf(Speakers.hasConnectPermission(context)) }
    var hasScan by remember { mutableStateOf(Speakers.hasScanPermission(context)) }
    var btOn by remember { mutableStateOf(Speakers.adapter(context)?.isEnabled == true) }
    var connected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var paired by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var seen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var busyAddress by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        hasConnect = Speakers.hasConnectPermission(context)
        hasScan = Speakers.hasScanPermission(context)
        btOn = Speakers.adapter(context)?.isEnabled == true
        paired =
            if (hasConnect && btOn) {
                runCatching { Speakers.adapter(context)?.bondedDevices.orEmpty().filter { Speakers.isAudio(it) } }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        scope.launch { connected = Speakers.connectedAddresses(context) }
    }

    fun connectOrSwitcher(device: BluetoothDevice) {
        busyAddress = device.address
        scope.launch {
            val ok = Speakers.connect(context, device)
            if (!ok) {
                Toast.makeText(context, R.string.resona_speakers_connect_in_system, Toast.LENGTH_LONG).show()
                Speakers.openOutputSwitcher(context)
            }
            delay(1_500)
            busyAddress = null
            refresh()
        }
    }

    val scan =
        remember {
            BluetoothScan(
                context.applicationContext,
                onFound = { device ->
                    seen = seen + device.address
                    if (found.none { it.address == device.address } && runCatching { device.bondState }.getOrNull() != BluetoothDevice.BOND_BONDED) found += device
                },
                onBond = { device, state ->
                    if (state == BluetoothDevice.BOND_BONDED) {
                        found.removeAll { it.address == device.address }
                        refresh()
                        connectOrSwitcher(device)
                    } else if (state == BluetoothDevice.BOND_NONE && busyAddress == device.address) {
                        busyAddress = null
                    }
                },
            )
        }
    DisposableEffect(scan) {
        scan.register()
        onDispose { scan.stop() }
    }

    fun search() {
        found.clear()
        seen = emptySet()
        searched = true
        searching = scan.start()
        if (searching) {
            scope.launch {
                delay(12_000)
                scan.stop()
                scan.register()
                searching = false
            }
        }
    }

    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }
    val enableBluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }

    fun askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        // First opening: a short explanation is visible, then Android asks once.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (!hasConnect || !hasScan) && !prefs.getBoolean(ASKED, false)) {
            prefs.edit().putBoolean(ASKED, true).apply()
            delay(900)
            askPermissions()
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.resona_speakers),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        SheetCard {
            OutputRow(
                icon = output.icon,
                title = output.label(),
                subtitle = stringResource(R.string.resona_speakers_connected),
                checked = true,
                tag = "speakers.current",
            )
            val currentName = output.name
            if (output.isExternal) {
                Divider()
                OutputRow(
                    icon = R.drawable.resona_phone,
                    title = stringResource(R.string.resona_speakers_this_device),
                    subtitle = null,
                    onClick = { Speakers.openOutputSwitcher(context) },
                )
            }
            paired.filter { it.name != currentName || output.kind != OutputKind.BLUETOOTH }.forEach { device ->
                Divider()
                val isConnected = device.address in connected
                OutputRow(
                    icon = if (Speakers.looksLikeHeadphones(device.name.orEmpty())) R.drawable.resona_headphones else R.drawable.resona_speaker,
                    title = device.name ?: device.address,
                    subtitle =
                        stringResource(
                            when {
                                busyAddress == device.address -> R.string.resona_speakers_connecting
                                isConnected -> R.string.resona_speakers_connected
                                device.address in seen -> R.string.resona_speakers_in_range
                                else -> R.string.resona_speakers_paired
                            },
                        ),
                    busy = busyAddress == device.address,
                    onClick = { if (!isConnected) connectOrSwitcher(device) else Speakers.openOutputSwitcher(context) },
                )
            }
            Divider()
            OutputRow(
                icon = R.drawable.resona_list,
                title = stringResource(R.string.resona_speakers_choose),
                subtitle = stringResource(R.string.resona_speakers_choose_hint),
                tag = "speakers.choose",
                onClick = { Speakers.openOutputSwitcher(context) },
            )
        }

        SheetCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !hasConnect || (!hasScan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
                        Text(stringResource(R.string.resona_speakers_permission), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { askPermissions() }) { Text(stringResource(R.string.resona_speakers_allow)) }
                    }
                    !btOn -> {
                        Text(stringResource(R.string.resona_speakers_bt_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        }) { Text(stringResource(R.string.resona_speakers_bt_on)) }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.resona_speakers_new),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        TextButton(onClick = { search() }, enabled = !searching, modifier = Modifier.testTag("speakers.search")) {
                            Text(stringResource(if (searching) R.string.resona_speakers_searching else R.string.resona_speakers_search))
                        }
                        if (searched && !searching && found.isEmpty()) {
                            Text(stringResource(R.string.resona_speakers_none_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            found.forEach { device ->
                Divider()
                OutputRow(
                    icon = if (Speakers.looksLikeHeadphones(device.name.orEmpty())) R.drawable.resona_headphones else R.drawable.resona_speaker,
                    title = device.name ?: device.address,
                    subtitle = stringResource(if (busyAddress == device.address) R.string.resona_speakers_pairing else R.string.resona_speakers_pair),
                    busy = busyAddress == device.address,
                    onClick = {
                        busyAddress = device.address
                        scan.stop()
                        scan.register()
                        searching = false
                        if (!runCatching { device.createBond() }.getOrDefault(false)) busyAddress = null
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun OutputRow(
    icon: Int,
    title: String,
    subtitle: String?,
    checked: Boolean = false,
    busy: Boolean = false,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            checked -> Icon(painterResource(R.drawable.check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
    }
}
