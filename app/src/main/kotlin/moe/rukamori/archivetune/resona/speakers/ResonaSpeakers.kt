/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.R

/**
 * "Speakers" (round 12), same sheet as the iPad: where the music plays now,
 * this device, paired Bluetooth audio devices (connected / in range / paired),
 * "Search nearby" for new ones (pair, then connect) and the system output
 * switcher. Only audio devices are ever listed.
 */
enum class OutputKind { BUILT_IN, BLUETOOTH, HEADPHONES, CAST, CAR, TV, OTHER }

data class AudioOutput(val kind: OutputKind, val name: String) {
    val isExternal: Boolean get() = kind != OutputKind.BUILT_IN

    /** Same meaning as the iPad's SF Symbols: speaker, headphones, cast, car, TV. */
    val icon: Int
        get() =
            when (kind) {
                OutputKind.BUILT_IN -> R.drawable.resona_phone
                OutputKind.BLUETOOTH -> if (Speakers.looksLikeHeadphones(name)) R.drawable.resona_headphones else R.drawable.resona_speaker
                OutputKind.HEADPHONES -> R.drawable.resona_headphones
                OutputKind.CAST -> R.drawable.resona_cast
                OutputKind.CAR -> R.drawable.resona_car
                OutputKind.TV -> R.drawable.resona_tv
                OutputKind.OTHER -> R.drawable.volume_up
            }

    companion object {
        val BUILT_IN = AudioOutput(OutputKind.BUILT_IN, "")
    }
}

object Speakers {
    private const val TAG = "ResonaSpeakers"

    fun kindFor(type: Int): OutputKind =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.BUILT_IN
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> OutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG,
            -> OutputKind.HEADPHONES
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> OutputKind.CAST
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputKind.TV
            else -> OutputKind.OTHER
        }

    /** Media follows Android's routing order: Bluetooth, then wired/USB, then the speaker. */
    fun pick(outputs: List<Pair<Int, String>>): AudioOutput {
        val rank = listOf(OutputKind.BLUETOOTH, OutputKind.HEADPHONES, OutputKind.TV)
        for (kind in rank) {
            outputs.firstOrNull { kindFor(it.first) == kind }?.let { (type, name) ->
                // The Bluetooth SCO entry is the call channel of a headset: same device name.
                return AudioOutput(kindFor(type), name)
            }
        }
        return AudioOutput.BUILT_IN
    }

    fun looksLikeHeadphones(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("buds", "pods", "headphone", "kopfhörer", "headset", "wh-", "wf-").any { it in lower }
    }

    fun current(context: Context): AudioOutput {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            .map { it.type to it.productName.toString() }
        return pick(outputs)
    }

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Audio/video class or an audio service bit: speakers, headphones, car kits. */
    fun isAudio(majorClass: Int?, hasAudioService: Boolean): Boolean =
        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO || hasAudioService

    @SuppressLint("MissingPermission")
    fun isAudio(device: BluetoothDevice): Boolean {
        val cls = device.bluetoothClass ?: return false
        return isAudio(cls.majorDeviceClass, cls.hasService(BluetoothClass.Service.AUDIO))
    }

    /** System output switcher (Android's own list, connects paired devices on tap). */
    fun openOutputSwitcher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return true
        }
        val intent =
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", context.packageName)
        if (runCatching { context.sendBroadcast(intent) }.isSuccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return true
        return runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** Addresses of connected media devices (A2DP, headset, LE audio). Needs BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    suspend fun connectedAddresses(context: Context): Set<String> {
        val adapter = adapter(context) ?: return emptySet()
        if (!hasConnectPermission(context) || !adapter.isEnabled) return emptySet()
        val profiles = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
        val out = HashSet<String>()
        for (profile in profiles) {
            val proxy = proxy(context, adapter, profile) ?: continue
            runCatching { proxy.connectedDevices.forEach { out += it.address } }
            adapter.closeProfileProxy(profile, proxy)
        }
        return out
    }

    private suspend fun proxy(context: Context, adapter: BluetoothAdapter, profile: Int): BluetoothProfile? {
        val result = CompletableDeferred<BluetoothProfile?>()
        val ok = runCatching {
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        result.complete(proxy)
                    }

                    override fun onServiceDisconnected(p: Int) {
                        result.complete(null)
                    }
                },
                profile,
            )
        }.getOrDefault(false)
        if (!ok) return null
        return withTimeoutOrNull(2_000) { result.await() }
    }

    /**
     * Connect a paired device as speaker: Android only offers this to system
     * apps (BluetoothA2dp.connect is a system API). Tried by reflection; when
     * Android refuses (the normal case on Android 13+), the caller opens the
     * system output switcher, where one tap connects it.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, device: BluetoothDevice): Boolean {
        val adapter = adapter(context) ?: return false
        val proxy = proxy(context, adapter, BluetoothProfile.A2DP) as? BluetoothA2dp ?: return false
        return try {
            val method = BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
            (method.invoke(proxy, device) as? Boolean) == true
        } catch (e: Throwable) {
            Log.i(TAG, "direct connect not allowed: ${e.javaClass.simpleName}")
            false
        } finally {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
    }
}

/** Current output, updated whenever Android adds or removes an output. */
class AudioOutputWatcher(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _current = MutableStateFlow(Speakers.current(context))
    val current: StateFlow<AudioOutput> = _current.asStateFlow()

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                _current.value = Speakers.current(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                _current.value = Speakers.current(context)
            }
        }

    fun start() {
        _current.value = Speakers.current(context)
        audio.registerAudioDeviceCallback(callback, null)
    }

    fun stop() {
        audio.unregisterAudioDeviceCallback(callback)
    }
}

/** One Bluetooth audio device in the sheet. */
data class SpeakerDevice(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val connected: Boolean,
    val inRange: Boolean,
    val headphones: Boolean,
)

/** Classic discovery (≈ 12 s), audio devices only; bonding state changes too. */
class BluetoothScan(private val context: Context, private val onFound: (BluetoothDevice) -> Unit, private val onBond: (BluetoothDevice, Int) -> Unit) {
    private val receiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, intent: Intent) {
                val device = deviceFrom(intent) ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> if (Speakers.isAudio(device)) onFound(device)
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                        onBond(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                }
            }
        }
    private var registered = false

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!Speakers.hasScanPermission(context)) return false
        val adapter = Speakers.adapter(context) ?: return false
        register()
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        return runCatching { adapter.startDiscovery() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (Speakers.hasScanPermission(context)) runCatching { Speakers.adapter(context)?.cancelDiscovery() }
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
