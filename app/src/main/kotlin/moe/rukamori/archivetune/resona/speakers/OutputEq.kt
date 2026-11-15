/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EqualizerAutoHeadroomEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostStrengthKey
import moe.rukamori.archivetune.constants.EqualizerCustomProfilesJsonKey
import moe.rukamori.archivetune.constants.EqualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.constants.EqualizerSelectedProfileIdKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerStrengthKey
import moe.rukamori.archivetune.utils.dataStore

/**
 * EQ per output device (round 12, same rules, texts and icons as the iPad's
 * OutputEqualizer.swift): the equalizer is remembered per output (phone
 * speaker, "Soundcore Space One", "Echo Dot") and switches when the output
 * changes, with a short hint "EQ: <preset> for <device>".
 *
 * - "Same for all devices" on: every device uses the default EQ, edits change it.
 * - Off: a device with its own EQ uses it, any other device the default EQ.
 *   An edit on a device saves it as that device's own EQ; "Use default EQ"
 *   forgets it.
 */
@Serializable
data class EqSnapshot(
    val enabled: Boolean = false,
    val profileId: String = "flat",
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainEnabled: Boolean = false,
    val outputGainMb: Int = 0,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val autoHeadroomEnabled: Boolean = false,
)

@Serializable
data class OutputEqDevice(val name: String, val eq: EqSnapshot)

@Serializable
data class OutputEqState(
    val sameForAll: Boolean = false,
    val standard: EqSnapshot? = null,
    val devices: Map<String, OutputEqDevice> = emptyMap(),
)

/** What the hint calls the EQ. */
sealed interface EqLabel {
    data object Off : EqLabel
    data object Flat : EqLabel
    data class System(val number: Int) : EqLabel
    data class Profile(val name: String) : EqLabel
    data object Custom : EqLabel
}

/** Pure rules, unit-tested; the same functions exist in Swift. */
object OutputEqRules {
    const val BUILT_IN_KEY = "builtin"

    fun key(output: AudioOutput): String =
        if (!output.isExternal) BUILT_IN_KEY else "${output.kind.name.lowercase()}:${output.name.trim().lowercase()}"

    fun resolve(state: OutputEqState, key: String): EqSnapshot? =
        if (state.sameForAll) state.standard else state.devices[key]?.eq ?: state.standard

    fun hasOwn(state: OutputEqState, key: String): Boolean = !state.sameForAll && key in state.devices

    fun record(state: OutputEqState, key: String, name: String, eq: EqSnapshot): OutputEqState =
        if (state.sameForAll) state.copy(standard = eq) else state.copy(devices = state.devices + (key to OutputEqDevice(name, eq)))

    fun useStandard(state: OutputEqState, key: String): OutputEqState = state.copy(devices = state.devices - key)

    /** What to apply when the output becomes [key]; null = nothing changes. */
    fun switchTo(state: OutputEqState, key: String, current: EqSnapshot): EqSnapshot? =
        resolve(state, key)?.takeIf { it != current }

    fun label(eq: EqSnapshot, profileNames: Map<String, String>): EqLabel =
        when {
            !eq.enabled -> EqLabel.Off
            eq.profileId == "flat" -> EqLabel.Flat
            eq.profileId.startsWith("system:") -> eq.profileId.substringAfter(':').toIntOrNull()?.let { EqLabel.System(it + 1) } ?: EqLabel.Custom
            eq.profileId.startsWith("profile:") -> profileNames[eq.profileId.substringAfter(':')]?.let { EqLabel.Profile(it) } ?: EqLabel.Custom
            else -> EqLabel.Custom
        }
}

object OutputEq {
    private const val TAG = "ResonaOutputEq"
    private val StateKey = stringPreferencesKey("resonaOutputEq")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _output = MutableStateFlow(AudioOutput.BUILT_IN)
    val output: StateFlow<AudioOutput> = _output.asStateFlow()
    private val _state = MutableStateFlow(OutputEqState())
    val state: StateFlow<OutputEqState> = _state.asStateFlow()

    /** After a switch: preference changes until this snapshot arrives are ours, not edits. */
    private var pending: EqSnapshot? = null
    private var pendingUntil = 0L
    private var context: Context? = null

    fun start(app: Context) {
        if (context != null) return
        val ctx = app.applicationContext
        context = ctx
        val watcher = AudioOutputWatcher(ctx)
        scope.launch {
            runCatching {
                val prefs = ctx.dataStore.data.first()
                var s = decode(prefs[StateKey])
                if (s.standard == null) {
                    s = s.copy(standard = snapshot(prefs))
                    save(ctx, s)
                }
                _state.value = s
                // Output first (may switch), then follow EQ edits.
                Handler(Looper.getMainLooper()).post { runCatching { watcher.start() } }
                onOutput(watcher.current.value)
                launch { watcher.current.collect { onOutput(it) } }
                launch { ctx.dataStore.data.map(::snapshot).distinctUntilChanged().collect { onEq(it) } }
            }.onFailure { Log.w(TAG, "start failed", it) }
        }
    }

    fun setSameForAll(on: Boolean) {
        val ctx = context ?: return
        scope.launch {
            mutex.withLock {
                if (_state.value.sameForAll == on) return@withLock
                var s = _state.value
                if (on) s = s.copy(standard = snapshot(ctx.dataStore.data.first()))
                s = s.copy(sameForAll = on)
                _state.value = s
                save(ctx, s)
                applyResolved(ctx, hint = false)
            }
        }
    }

    fun useStandard() {
        val ctx = context ?: return
        scope.launch {
            mutex.withLock {
                val s = OutputEqRules.useStandard(_state.value, OutputEqRules.key(_output.value))
                _state.value = s
                save(ctx, s)
                applyResolved(ctx, hint = false)
            }
        }
    }

    private suspend fun onOutput(new: AudioOutput) {
        val ctx = context ?: return
        mutex.withLock {
            val changed = OutputEqRules.key(new) != OutputEqRules.key(_output.value)
            _output.value = new
            if (changed) applyResolved(ctx, hint = true)
        }
    }

    private suspend fun onEq(eq: EqSnapshot) {
        val ctx = context ?: return
        mutex.withLock {
            pending?.let { expected ->
                if (SystemClock.elapsedRealtime() < pendingUntil) {
                    if (eq == expected) pending = null
                    return
                }
                pending = null
            }
            val key = OutputEqRules.key(_output.value)
            if (eq == OutputEqRules.resolve(_state.value, key)) return
            val s = OutputEqRules.record(_state.value, key, displayName(ctx, _output.value), eq)
            _state.value = s
            save(ctx, s)
        }
    }

    private suspend fun applyResolved(ctx: Context, hint: Boolean) {
        val prefs = ctx.dataStore.data.first()
        val target = OutputEqRules.switchTo(_state.value, OutputEqRules.key(_output.value), snapshot(prefs)) ?: return
        pending = target
        pendingUntil = SystemClock.elapsedRealtime() + 3_000
        ctx.dataStore.edit { write(it, target) }
        Log.i(TAG, "EQ switched for ${_output.value.kind} (${target.profileId}, enabled=${target.enabled})")
        if (hint) {
            val text = hintText(ctx, target, displayName(ctx, _output.value), profileNames(prefs))
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        }
    }

    fun displayName(ctx: Context, output: AudioOutput): String =
        if (!output.isExternal || output.name.isBlank()) ctx.getString(R.string.resona_speakers_this_device) else output.name

    fun presetName(ctx: Context, eq: EqSnapshot, profileNames: Map<String, String>): String =
        when (val label = OutputEqRules.label(eq, profileNames)) {
            EqLabel.Off -> ctx.getString(R.string.resona_eq_off)
            EqLabel.Flat -> ctx.getString(R.string.eq_flat)
            is EqLabel.System -> ctx.getString(R.string.eq_preset_number, label.number)
            is EqLabel.Profile -> label.name
            EqLabel.Custom -> ctx.getString(R.string.resona_eq_custom)
        }

    fun hintText(ctx: Context, eq: EqSnapshot, device: String, profileNames: Map<String, String>): String =
        ctx.getString(R.string.resona_eq_hint, presetName(ctx, eq, profileNames), device)

    private fun profileNames(prefs: Preferences): Map<String, String> =
        runCatching {
            json.decodeFromString<moe.rukamori.archivetune.playback.EqProfilesPayload>(prefs[EqualizerCustomProfilesJsonKey].orEmpty())
                .profiles.associate { it.id to it.name }
        }.getOrDefault(emptyMap())

    fun snapshot(prefs: Preferences): EqSnapshot =
        EqSnapshot(
            enabled = prefs[EqualizerEnabledKey] ?: false,
            profileId = prefs[EqualizerSelectedProfileIdKey] ?: "flat",
            bandLevelsMb = prefs[EqualizerBandLevelsMbKey]?.let { runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull() }.orEmpty(),
            outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
            outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
            bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
            bassBoostStrength = prefs[EqualizerBassBoostStrengthKey] ?: 0,
            virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
            virtualizerStrength = prefs[EqualizerVirtualizerStrengthKey] ?: 0,
            autoHeadroomEnabled = prefs[EqualizerAutoHeadroomEnabledKey] ?: false,
        )

    private fun write(prefs: MutablePreferences, eq: EqSnapshot) {
        prefs[EqualizerEnabledKey] = eq.enabled
        prefs[EqualizerSelectedProfileIdKey] = eq.profileId
        prefs[EqualizerBandLevelsMbKey] = json.encodeToString(eq.bandLevelsMb)
        prefs[EqualizerOutputGainEnabledKey] = eq.outputGainEnabled
        prefs[EqualizerOutputGainMbKey] = eq.outputGainMb
        prefs[EqualizerBassBoostEnabledKey] = eq.bassBoostEnabled
        prefs[EqualizerBassBoostStrengthKey] = eq.bassBoostStrength
        prefs[EqualizerVirtualizerEnabledKey] = eq.virtualizerEnabled
        prefs[EqualizerVirtualizerStrengthKey] = eq.virtualizerStrength
        prefs[EqualizerAutoHeadroomEnabledKey] = eq.autoHeadroomEnabled
    }

    fun decode(raw: String?): OutputEqState =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<OutputEqState>(it) }.getOrNull() } ?: OutputEqState()

    fun encode(state: OutputEqState): String = json.encodeToString(state)

    private suspend fun save(ctx: Context, state: OutputEqState) {
        ctx.dataStore.edit { it[StateKey] = encode(state) }
    }
}
